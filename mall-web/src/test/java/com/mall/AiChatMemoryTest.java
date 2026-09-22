package com.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.api.dto.ChatDTO;
import com.mall.common.exception.BusinessException;
import com.mall.service.ai.AiChatServiceImpl;
import com.mall.service.ai.AiProperties;
import com.mall.service.ai.ChatMessage;
import com.mall.service.ai.ChatTurn;
import com.mall.service.ai.LlmClient;
import com.mall.service.ai.LlmResponse;
import com.mall.storage.ChatMemoryStore;
import com.mall.storage.memory.InMemoryChatMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多轮对话记忆的单元测试。
 *
 * <p><b>为什么不起 {@code @SpringBootTest}</b>：本测试要验证的是「历史有没有被正确拼进
 * 请求」，而这件事完全发生在普通对象之间。直接 new 出来测，一秒内跑完，
 * 不用连数据库也不用真调模型 —— 上个测试 {@code LlmClientTest} 打真接口只是例外。</p>
 *
 * <p><b>这个测试在防什么</b>：AI 历史出错是<b>静默的</b> —— 接口照样 200，
 * 只是模型忽然"失忆"。所以必须把「第 N 轮发出去的 messages 长什么样」钉死。
 * 如果哪天有人把 append 挪到调模型之前，第 ④ 个用例会立刻变红。</p>
 */
class AiChatMemoryTest {

    @Test
    @DisplayName("首轮只发 system + user 两条，并返回新建的会话标识")
    void firstTurnSendsSystemAndUserOnly() {
        RecordingLlmClient llm = new RecordingLlmClient();
        AiChatServiceImpl service = newService(llm);

        ChatTurn turn = service.chat(dto(null, "你好"));

        // 首轮必须新建一个会话标识回传，否则客户端下一轮无处可取
        assertThat(turn.conversationId()).isNotBlank();
        assertThat(llm.captured).hasSize(1);
        assertThat(llm.captured.get(0))
                .extracting(ChatMessage::role)
                .containsExactly("system", "user");
    }

    @Test
    @DisplayName("第二轮请求必须带上第一轮历史，顺序为 system → 历史 → 本轮")
    void secondTurnCarriesFirstTurnHistory() {
        RecordingLlmClient llm = new RecordingLlmClient();
        AiChatServiceImpl service = newService(llm);

        ChatTurn turn1 = service.chat(dto(null, "你好"));
        service.chat(dto(turn1.conversationId(), "再见"));

        assertThat(llm.captured).hasSize(2);

        // 诊断价值：如果这里是 2 条，说明历史没拼上；
        // 如果是 3 条且缺 assistant，说明 append 只写了半截。
        List<ChatMessage> secondTurn = llm.captured.get(1);
        assertThat(secondTurn)
                .extracting(ChatMessage::role)
                .containsExactly("system", "user", "assistant", "user");
        assertThat(secondTurn.subList(1, 4))
                .extracting(ChatMessage::content)
                .containsExactly("你好", "回复1", "再见");
    }

    @Test
    @DisplayName("不同会话互不串味")
    void conversationsAreIsolated() {
        RecordingLlmClient llm = new RecordingLlmClient();
        AiChatServiceImpl service = newService(llm);

        ChatTurn turnA = service.chat(dto(null, "A轮1"));
        service.chat(dto(null, "B轮1"));
        service.chat(dto(turnA.conversationId(), "A轮2"));

        // 第 3 次请求是 A 的第二轮，里面绝不该出现 B 的内容
        assertThat(llm.captured.get(2))
                .extracting(ChatMessage::content)
                .doesNotContain("B轮1");
        assertThat(llm.captured.get(2))
                .extracting(ChatMessage::content)
                .contains("A轮1");
    }

    @Test
    @DisplayName("模型调用失败时历史一条都不落库")
    void failedModelCallLeavesNoTrace() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        AiChatServiceImpl service = new AiChatServiceImpl(new FailingLlmClient(), store);

        assertThatThrownBy(() -> service.chat(dto("conv-fail", "你好")))
                .isInstanceOf(BusinessException.class);

        // 这就是「先调模型、后落库」要守住的东西：
        // 一旦落库，历史末尾会留下没有 assistant 的 user，
        // 下一轮模型会以为该自己回答 —— 且这个错位永久留在历史里。
        assertThat(store.getHistory("conv-fail")).isEmpty();
    }

    @Test
    @DisplayName("超过上限时只取最近 N 条，且截断点落在 user 上")
    void historyIsTruncatedToRecentMessages() {
        RecordingLlmClient llm = new RecordingLlmClient();
        AiChatServiceImpl service = newService(llm);

        String conversationId = null;
        for (int i = 1; i <= 13; i++) {
            conversationId = service.chat(dto(conversationId, "第" + i + "轮")).conversationId();
        }

        // 第 13 轮时历史已有 24 条，上限 20 → 只取最近 20 条
        List<ChatMessage> lastRequest = llm.captured.get(12);
        assertThat(lastRequest).hasSize(1 + 20 + 1);

        assertThat(lastRequest.get(0).role()).isEqualTo("system");
        // 截断后第一条历史必须是 user —— 若把一对 user/assistant 切成半对，
        // 模型会看到"最后一句是我自己说的"这种错位
        assertThat(lastRequest.get(1).role()).isEqualTo("user");
        assertThat(lastRequest.get(1).content()).isEqualTo("第3轮");
    }

    private AiChatServiceImpl newService(LlmClient llmClient) {
        return new AiChatServiceImpl(llmClient, new InMemoryChatMemoryStore());
    }

    private static ChatDTO dto(String conversationId, String message) {
        ChatDTO dto = new ChatDTO();
        dto.setConversationId(conversationId);
        dto.setMessage(message);
        return dto;
    }

    /**
     * 会记录每次收到什么 messages 的假客户端。
     *
     * <p>用继承而不是引入 mock 框架：{@code LlmClient.chat} 是普通 public 方法，
     * 覆写它就能截住调用。单元测试里绝不该打真接口 —— 那是真金白银的 token。</p>
     */
    private static class RecordingLlmClient extends LlmClient {

        private final List<List<ChatMessage>> captured = new ArrayList<>();
        private int calls;

        RecordingLlmClient() {
            super(new AiProperties(), new ObjectMapper());
        }

        @Override
        public LlmResponse chat(List<ChatMessage> messages) {
            captured.add(List.copyOf(messages));
            calls++;
            return new LlmResponse("回复" + calls, 10, 5, 15, 1L);
        }
    }

    /** 总是失败的假客户端，用来验证「失败不落库」。 */
    private static class FailingLlmClient extends LlmClient {

        FailingLlmClient() {
            super(new AiProperties(), new ObjectMapper());
        }

        @Override
        public LlmResponse chat(List<ChatMessage> messages) {
            throw new BusinessException(502, "调用大模型失败");
        }
    }
}
