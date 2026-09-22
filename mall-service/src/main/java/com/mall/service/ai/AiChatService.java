package com.mall.service.ai;

import com.mall.api.dto.ChatDTO;

public interface AiChatService {

    /**
     * 处理一轮对话。
     *
     * <p>返回 {@link ChatTurn} 而不是 {@code LlmResponse}：一轮对话的产物除了模型回复，
     * 还有本轮所属的会话标识（首轮由服务端新建，必须回传客户端）。</p>
     */
    ChatTurn chat(ChatDTO dto);

}
