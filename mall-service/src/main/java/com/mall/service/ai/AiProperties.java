package com.mall.service.ai;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 配置，对应 application.yml 的 mall.ai.* 节点。
 *
 * <p>用 @ConfigurationProperties 而不是每个字段一个 @Value：
 * 前者把一组配置收拢成一个可注入的对象，字段一多就不会散落各处，
 * 而且改配置时不用改代码 —— 这是 Spring Boot 的标准做法。</p>
 */

@Data
@Component
@Slf4j
@ConfigurationProperties(prefix = "mall.ai")
public class AiProperties {
    private boolean enabled = Boolean.TRUE;
    /** 对话补全接口地址（OpenAI 兼容协议） */
    private String baseUrl = "https://api.deepseek.com/chat/completions";

    /**
     * API Key。
     * 注意 yml 里写的是 ${DEEPSEEK_API_KEY:} —— 冒号后留空表示「取不到就用空字符串」，
     * 这样在没配 Key 的机器上应用照样能启动。
     */
    private String apiKey;

    /**
     * 模型名。
     *
     * <p>DeepSeek 当前可用的名字是 {@code deepseek-v4-flash}（快、便宜，适合聊天/总结/Agent）
     * 与 {@code deepseek-v4-pro}（推理更强，适合复杂代码与多步推理）。
     * <b>旧名 {@code deepseek-chat} / {@code deepseek-reasoner} 已于 2026-07-24 停用，
     * 继续使用会直接返回错误，且没有静默回退。</b></p>
     *
     * <p>模型名写错不会在启动时暴露 —— 应用照样起得来，只有真正调用时才返回 400。
     * 所以换模型后必须跑一次真实对话验证，不能只看启动成功。</p>
     */
    private String model = "deepseek-v4-flash";

    /** 单次请求超时（秒）。大模型比普通接口慢，给足 */
    private Integer timeoutSeconds = 60;

    /** 温度：0 稳定、1 发散 */
    private Double temperature = 0.7;

    @PostConstruct
    public void logConfig() {
        String keyState = (apiKey == null || apiKey.isBlank())
                ? "【未配置】"
                : "已配置(长度=" + apiKey.length() + ")";
        log.info("mall.ai 配置：enabled={}, model={}, apiKey={}", enabled, model, keyState);
    }
}
