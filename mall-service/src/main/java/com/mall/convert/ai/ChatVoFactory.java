package com.mall.convert.ai;

import com.mall.api.vo.ChatVO;
import com.mall.convert.VoFactory;
import com.mall.service.ai.ChatTurn;
import org.springframework.stereotype.Component;

/**
 * AI 对话 VO 装配工厂。
 *
 * <p>引入本类之前，装配代码直接写在 {@code AiChatController} 里：</p>
 * <pre>{@code
 * ChatVO vo = new ChatVO();
 * vo.setReply(resp.content());
 * vo.setPromptTokens(resp.promptTokens());
 * ...  // 还有 3 行
 * return Result.success(vo);
 * }</pre>
 *
 * <p>问题不在于"多了 5 行"，而在于<b>职责错位</b>：Controller 的职责是
 * HTTP 编排（取参数、调服务、包统一响应体）。一旦它开始搬运字段，
 * 后续每加一个响应字段都要动 Controller，Controller 会逐渐变成一个
 * 什么都干的上帝类 —— 这正是要避免的。</p>
 *
 * <p><b>为什么源类型是 {@link ChatTurn} 而不是 {@code LlmResponse}</b>：
 * {@code LlmResponse} 是模型协议的映射（content + 4 个 token 字段），
 * 而 {@code ChatVO} 是给前端的出参，还要带上会话标识。
 * 用一个中间类型承接，才能让两边各自保持纯粹 —— 否则只能往
 * {@code LlmResponse} 里塞业务字段，那是把外部契约和内部模型混在一起。</p>
 */
@Component
public class ChatVoFactory implements VoFactory<ChatTurn, ChatVO> {

    @Override
    public ChatVO create(ChatTurn source) {
        if (source == null) {
            return null;
        }
        ChatVO vo = new ChatVO();
        vo.setConversationId(source.conversationId());

        var response = source.response();
        if (response != null) {
            vo.setReply(response.content());
            vo.setPromptTokens(response.promptTokens());
            vo.setCompletionTokens(response.completionTokens());
            vo.setTotalTokens(response.totalTokens());
            vo.setCostMs(response.costMs());
        }
        return vo;
    }
}
