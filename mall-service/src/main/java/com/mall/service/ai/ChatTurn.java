package com.mall.service.ai;

/**
 * 一轮对话的结果。
 *
 * <p><b>为什么不直接返回 {@link LlmResponse}</b>：一轮对话的产物不只有「模型的回复」，
 * 还有<b>这一轮的会话标识</b> —— 首轮时它是服务端新建的，必须回传给客户端，
 * 否则客户端下一轮无处可取，每轮都会开一个新会话（表现为「模型失忆」）。</p>
 *
 * <p><b>为什么也不把会话标识塞进 {@code LlmResponse}</b>：那是模型协议层的响应结构，
 * 换成任何 OpenAI 兼容服务返回的都是同样的五个字段，属于外部契约，
 * 不该被业务字段污染（同 {@code ChatMessage} 的设计原则）。</p>
 *
 * <p>于是拆出这一层：{@code LlmResponse} 保持纯粹的协议映射，
 * 会话标识由本类型承载。{@code ChatVoFactory} 从本类型装配 {@code ChatVO}。</p>
 *
 * @param conversationId 本轮所属会话；首轮为服务端新建
 * @param response       模型响应原文
 */
public record ChatTurn(String conversationId, LlmResponse response) {
}
