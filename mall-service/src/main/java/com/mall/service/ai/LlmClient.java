package com.mall.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最简大模型客户端 —— 只用 JDK 自带的 HttpClient，不引任何 AI 框架。
 *
 * <p>把框架全部拿掉，才能看清：大模型调用就是「一次 HTTP POST + 一段 JSON」。
 * 后面无论换 LangChain4j 还是 Spring AI，都只是把这几十行包装起来而已。</p>
 *
 * <p>DeepSeek 的接口是 OpenAI 兼容协议，所以请求体形如
 * {@code {"model": "...", "messages": [{"role":"user","content":"..."}]}}，
 * 响应取 {@code choices[0].message.content}。换成任何 OpenAI 兼容的服务
 * （通义、Kimi、本地 vLLM）都只需要改 base-url 和 model。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    /**
     * 配置对象。
     *
     * <p><b>这里必须是 final。</b>类上用的是 Lombok 的 {@code @RequiredArgsConstructor}，
     * 它只为「final 且没有初始化器」的字段生成构造参数。少写 final 的话字段不会被注入，
     * 运行时是 null —— 编译能过、启动能过，第一次调用才 NPE。</p>
     */
    private final AiProperties aiProperties;

    private final ObjectMapper objectMapper;

    /**
     * JDK 11+ 自带的 HttpClient，线程安全，全应用共用一个实例。
     *
     * <p>它是 final 且有初始化器，因此不会被 {@code @RequiredArgsConstructor}
     * 纳入构造参数 —— 这正是我们想要的。</p>
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 发一次对话请求。
     *
     * @param messages role/content 列表。多轮历史必须由调用方拼好 —— 服务端不记任何东西
     */
    public LlmResponse chat(List<ChatMessage> messages) {
        if (!aiProperties.isEnabled()) {
            throw new BusinessException(503, "AI 功能当前未启用");
        }
        // 注意这里是 !hasText —— 判断的是「没配 Key」。写反的话就会变成
        // 「配了 Key 反而报未配置」，是本项目实际踩过的坑。
        if (!StringUtils.hasText(aiProperties.getApiKey())) {
            throw new BusinessException(503, "未配置大模型 API Key，请设置环境变量 DEEPSEEK_API_KEY");
        }
        if (messages == null || messages.isEmpty()) {
            throw new BusinessException(400, "对话内容不能为空");
        }

        // ---------- ① 拼请求体 ----------
        // 用 LinkedHashMap 是为了字段顺序稳定，好看、也好和官方文档对照
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", aiProperties.getModel());
        body.put("messages", messages);
        body.put("temperature", aiProperties.getTemperature());

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new BusinessException(500, "构造请求体失败：" + e.getMessage());
        }

        // ---------- ② 发请求 ----------
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(aiProperties.getBaseUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + aiProperties.getApiKey())
                .timeout(Duration.ofSeconds(aiProperties.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            // 恢复中断标志再抛出 —— 吞掉中断标志会让上层失去感知能力
            Thread.currentThread().interrupt();
            throw new BusinessException(500, "调用大模型被中断");
        } catch (Exception e) {
            throw new BusinessException(502, "调用大模型失败：" + e.getMessage());
        }
        long costMs = System.currentTimeMillis() - start;

        if (response.statusCode() != 200) {
            // 原始响应只进日志，不返回前端 —— 避免泄露内部细节。
            // 这里的 body 是模型的报错原文（如 model not found），排查时非常有用，
            // 所以必须留在日志里，不能整个吞掉。
            log.warn("大模型返回非 200：status={}, model={}, body={}",
                    response.statusCode(), aiProperties.getModel(), response.body());
            throw new BusinessException(502, "大模型返回异常状态码 " + response.statusCode());
        }

        // ---------- ③ 解析响应 ----------
        try {
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            JsonNode usage = root.path("usage");
            // 只记录耗时与 token 用量，绝不记录 Key 或完整响应体
            log.info("大模型调用完成：model={}, 耗时={}ms, tokens={}",
                    aiProperties.getModel(), costMs, usage.path("total_tokens").asInt(0));
            return new LlmResponse(
                    content,
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    usage.path("total_tokens").asInt(0),
                    costMs);
        } catch (Exception e) {
            throw new BusinessException(500, "解析大模型响应失败：" + e.getMessage());
        }
    }
}
