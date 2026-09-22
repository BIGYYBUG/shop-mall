package com.mall.service.ai;

/**
 * 一次大模型调用的结果。
 *
 * <p>把它从 {@code LlmClient} 的内部类提出来成为顶层类型，原因是：
 * {@code ChatVoFactory} 需要引用它，而 VO 工厂去 import 一个"客户端类的嵌套类"
 * 会形成不必要的耦合。响应结构本身就是独立的概念 —— 换成任何 OpenAI 兼容的服务，
 * 返回的都是同样这五个字段。</p>
 *
 * @param content          模型回答正文
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 * @param totalTokens      合计 token 数
 * @param costMs           本次调用耗时（毫秒）
 */
public record LlmResponse(String content,
                          int promptTokens,
                          int completionTokens,
                          int totalTokens,
                          long costMs) {
}
