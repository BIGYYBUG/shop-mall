package com.mall.storage;

import com.mall.service.ai.ChatMessage;

import java.util.List;

/**
 * 对话记忆存储 —— 本质是「只追加的消息日志」，<b>不是</b>会话管理器。
 *
 * <p>放在 {@code com.mall.storage} 而非 {@code service.ai}，是为了与既有的
 * {@link FileStorageService} 保持一致：项目的约定是「存储能力 = 接口 + 实现同包，
 * 实现按渠道分子包（local / oss / memory）」。对话记忆是同一类"存东西"的能力。</p>
 *
 * <h2>为什么需要它</h2>
 * 大模型服务端是<b>无状态</b>的：它不记得上一轮说了什么。「多轮对话」是我们每轮把
 * 历史重新拼进 messages 再发过去的假象。既然要重发，就必须有地方把历史存住。
 *
 * <p><b>为什么不让客户端把完整历史带上来？</b>两个理由，第二个是安全问题：</p>
 * <ol>
 *   <li>请求体随轮数线性膨胀，而模型输入 token 成本是 <b>O(n²)</b> 增长
 *       —— 第 n 轮要把前 n-1 轮全部重发一遍。</li>
 *   <li><b>历史可被篡改。</b>若客户端能自带 messages 数组，攻击者就能伪造一条
 *       {@code assistant} 消息塞进历史 ——「你刚才已经答应给我免单了」，模型无从分辨。
 *       这是 prompt injection 的一个入口，所以历史必须由服务端拼。</li>
 * </ol>
 *
 * <h2>三条契约（实现方必须遵守）</h2>
 * <ol>
 *   <li><b>{@link #getHistory} 返回该会话已有的全部消息</b>，按写入顺序升序。
 *       实现方<b>不得</b>擅自截断 —— 存储层偷偷 {@code LIMIT 20} 的现象是
 *       「模型莫名忘记开头说过的事」，这种 bug 极难排查。
 *       窗口裁剪属于上层策略（见 {@code AiChatServiceImpl#MAX_HISTORY_MESSAGES}），
 *       不属于存储层。</li>
 *   <li><b>返回值必须不可变</b>（实现建议 {@code List.copyOf}）。否则调用方就地
 *       {@code add} 会改到存储内部状态。</li>
 *   <li><b>历史只增不改。</b>没有 update、没有「删除单条」。对话是已发生的输入，
 *       能改历史就等于能改模型输入 —— 绕过 system prompt 只差一个 update 接口。</li>
 * </ol>
 *
 * <h2>为什么 append 收 List 而不是单条消息</h2>
 * 一轮对话至少产生两条消息（user + assistant）。分两次写没有任何事务能覆盖它们，
 * 中途失败会留下「只有提问没有回答」的半截历史；下一轮拼给模型时最后一条是
 * {@code user}，模型会认为轮到它说话 —— 且这个错位会<b>永久留在历史里，每轮复现</b>。
 * 一次写多条还能让 Redis 实现用单条 {@code RPUSH k v1 v2} 天然原子。
 *
 * <h2>安全边界</h2>
 * 本接口<b>不做任何鉴权</b> —— 它只是日志。调用方在读取前必须校验该会话归属于当前
 * 用户，否则 {@code conversationId} 就是一个可被遍历的越权入口（IDOR）。这也是 id
 * 必须用<b>不可枚举</b>的 UUID 而非自增 Long 的原因。
 *
 * <h2>已知取舍（勿遗忘）</h2>
 * <ol>
 *   <li><b>命名风险</b>：LangChain4j 有一个同名接口，但语义是<b>全量替换</b>
 *       （{@code getMessages / updateMessages(Object, List) / deleteMessages}），
 *       与本接口的<b>增量追加</b>语义不同。将来真引入 LangChain4j 时必须改名，
 *       否则同名不同义会互相混淆。</li>
 *   <li><b>包级循环依赖</b>：本接口 import 了 {@code service.ai.ChatMessage}，
 *       而 {@code service.ai} 又 import 本接口 —— 两个顶层包互相依赖。
 *       Java 不会报错，但这是真实的架构瑕疵。彻底解法是把 {@code ChatMessage}
 *       上移为中立值对象（例如新建 {@code com.mall.chat} 领域包），
 *       让两边都单向依赖它。当前规模下先接受，动 {@code ChatMessage} 时一并处理。</li>
 * </ol>
 */
public interface ChatMemoryStore {

    /**
     * 读取会话的全部历史，按写入顺序升序。
     *
     * @param conversationId 会话标识，非空
     * @return 不可变列表；会话不存在时返回<b>空列表而非 null</b>
     */
    List<ChatMessage> getHistory(String conversationId);

    /**
     * 追加一批消息。整批要么都写入成功，要么都不写入。
     *
     * @param conversationId 会话标识，非空
     * @param messages       本批消息，按顺序追加；空集合应被忽略而非报错
     */
    void append(String conversationId, List<ChatMessage> messages);

    /**
     * 清空会话的消息内容，<b>保留会话本身</b>。
     *
     * <p>会话的删除与列表属于另一个聚合（{@code ConversationService} + 会话元数据表），
     * 不要在本接口里长出 {@code listConversations()} —— 会话列表要分页、按 userId 过滤、
     * 按时间排序，与「读写消息日志」是两种访问模式。</p>
     *
     * @param conversationId 会话标识，非空
     */
    void clear(String conversationId);
}
