package com.mall.service.ai;

import com.mall.api.dto.ChatDTO;
import com.mall.storage.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AI 对话服务实现。
 *
 * <p><b>本类负责"怎么问"</b>：拼系统提示词、组织 messages 结构、维护多轮历史。
 * <b>不负责"怎么发"</b> —— 那层 HTTP 细节在 {@link LlmClient}；
 * <b>也不负责"历史存哪"</b> —— 那是 {@link ChatMemoryStore}。
 * 这样把「提示词工程」「协议实现」「存储」三件事分开，将来换成 LangChain4j / Spring AI，
 * 本类的提示词一个字都不用改；存储换成 Redis / MySQL 也只换实现类，本类无感。</p>
 */
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements AiChatService {

    /**
     * 系统提示词：定义助手的身份与边界。
     *
     * <p>最后两条约束是刻意写的，而且很重要：</p>
     * <ol>
     *   <li><b>明确说出"现在还查不到数据"</b> —— 模型在不确定时倾向于编造（幻觉）。
     *       与其让它假装知道订单状态，不如让它老实说不知道。</li>
     *   <li><b>禁止编造具体数据</b> —— 商品价格、订单号一旦编错，用户是会当真的。</li>
     * </ol>
     *
     * <p>等下一阶段接上真正的工具后，这两条会改写成"需要查数据时调用工具"，
     * 模型的表现会立刻不一样。</p>
     */
    private static final String SYSTEM_PROMPT = """
            你是「商城助手」，服务于一个购物网站的用户。
            回答要求：简洁、准确、使用中文，不要啰嗦。

            当前你只能进行普通对话，还不具备查询商品或订单的能力。
            如果用户询问商品价格、库存、订单状态等具体数据，请如实告知你暂时查不到，
            并建议用户通过商城页面查看。绝对不要编造任何商品名、价格、订单号或状态。
            当用户问你是谁时，你说自己是：悦购：要告诉用户，希望用户能愉快的购物
            """;

    /**
     * 送进模型的历史条数上限（不含 system 与本轮）。
     *
     * <p><b>为什么必须截断</b>：模型 API 是无状态的，第 n 轮要把前 n-1 轮全部重发，
     * 所以输入 token 成本随轮数呈 <b>O(n²)</b> 增长。不截断的话，聊到几十轮就既超
     * 上下文窗口、又真金白银地烧钱。</p>
     *
     * <p><b>为什么这个常量在这里、而不是写进 store</b>：{@code ChatMemoryStore} 的职责是
     * 「存取」，裁剪是「策略」。把 LIMIT 写进存储层，将来想按 token 数（而非条数）裁剪
     * 就得回头改所有实现。放这里，将来升级成独立的 {@code ChatWindowPolicy} 也只动这一处。</p>
     *
     * <p><b>取最近 N 条</b>（不是最早 N 条）—— 对话的延续性依赖最近的上下文。</p>
     *
     * <p>上限取<b>偶数</b>，而每轮固定追加 2 条（user + assistant），所以截断点始终落在
     * {@code user} 上，不会把一对 user/assistant 切成半对 —— 历史上以 user 开头的
     * 好处是模型看到的是完整的"问答对"，不会出现"最后一句是我自己说的"这种错位。</p>
     */
    private static final int MAX_HISTORY_MESSAGES = 20;

    private final LlmClient llmClient;

    /**
     * <p><b>这里必须是 final。</b>类上用 Lombok 的 {@code @RequiredArgsConstructor}，
     * 它只为「final 且无初始化器」的字段生成构造参数。少写 final 就不会被注入，
     * 运行时是 null —— 编译能过、启动能过，第一次对话才 NPE。（本项目已踩过这个坑。）</p>
     */
    private final ChatMemoryStore chatHistoryStore;

    @Override
    public ChatTurn chat(ChatDTO dto) {
        // ① 解析会话标识：首轮没有就新建，并随结果回传给客户端。
        //    注意：这里不做"会话是否存在"的校验 —— 那是第 3 课有会话表之后的归属校验，
        //    也是防 IDOR 的关键一步（store 只是日志，不做鉴权）。
        String conversationId = StringUtils.hasText(dto.getConversationId())
                ? dto.getConversationId()
                : UUID.randomUUID().toString();

        // ② 取历史（不含本轮）。store 保证返回不可变列表，会话不存在时返回空列表
        List<ChatMessage> history = recentHistory(conversationId);

        // ③ 拼 messages：system 永远在最前 → 历史 → 本轮输入
        ChatMessage userMessage = ChatMessage.user(dto.getMessage());
        List<ChatMessage> messages = new ArrayList<>(history.size() + 2);
        messages.add(ChatMessage.system(SYSTEM_PROMPT));
        messages.addAll(history);
        messages.add(userMessage);

        // ④ 调模型。失败时这里直接抛出，历史一个字都不落库 —— 这是刻意的。
        //
        //    【为什么先调模型、后落库】
        //    如果倒过来（先写 user 再调模型），模型调用失败就会在历史末尾留下一条
        //    没有对应 assistant 的 user 消息。下一轮把这段历史拼给模型，最后一条是
        //    user，模型会认为"轮到我说了" —— 于是它去回答上一条已经失败的问题，
        //    答非所问；而且这个错位**永久留在历史里，每一轮都复现**。
        //    代价是"用户问过但失败"这件事模型不知道 —— 可以接受：用户会重发。
        LlmResponse response = llmClient.chat(messages);

        // ⑤ 成功之后才成批写入本轮的两条消息（user + assistant）。
        //    一次 append 传两条，是契约要求：分两次写没有任何事务能覆盖它们，
        //    中途失败同样会留下"只有提问没有回答"的半截历史。
        chatHistoryStore.append(conversationId, List.of(
                userMessage,
                ChatMessage.assistant(response.content())));

        return new ChatTurn(conversationId, response);
    }

    /**
     * 取最近 {@link #MAX_HISTORY_MESSAGES} 条历史。
     *
     * <p>截断放在这里而不是 store 里，是为了让 store 保持"全量返回"的简单契约 ——
     * 存储层偷偷加 LIMIT 的话，将来按 token 数裁窗时会发现改了存储却影响不到行为。</p>
     */
    private List<ChatMessage> recentHistory(String conversationId) {
        List<ChatMessage> history = chatHistoryStore.getHistory(conversationId);
        if (history.size() <= MAX_HISTORY_MESSAGES) {
            return history;
        }
        return history.subList(history.size() - MAX_HISTORY_MESSAGES, history.size());
    }
}
