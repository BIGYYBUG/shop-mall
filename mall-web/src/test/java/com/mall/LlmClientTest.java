package com.mall;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.exception.BusinessException;
import com.mall.service.ai.AiProperties;
import com.mall.service.ai.ChatMessage;
import com.mall.service.ai.LlmClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link LlmClient} 的单元测试。
 *
 * <p><b>为什么不用 {@code @SpringBootTest}</b>：这个类不需要容器 ——
 * 它的依赖就是两个普通对象（配置对象 + ObjectMapper）。直接 new 出来测，
 * 一秒内跑完，也不用连数据库。只有真需要验证 Bean 装配时才值得启动整个上下文。</p>
 *
 * <p><b>这个测试在防什么</b>：本项目实际踩过一个坑 ——
 * 配置字段少写 {@code final}，Lombok 的 {@code @RequiredArgsConstructor}
 * 就不会把它纳入构造参数，字段永远是 null，编译能过、启动能过，
 * 第一次调用才 NPE。第 ① 个用例就是这道防线。</p>
 *
 * <p><b>维护提醒</b>：{@code chat} 的参数类型从 {@code List<Map<String,String>>}
 * 改成 {@code List<ChatMessage>} 时，本测试必须同步改 ——
 * 直接注释掉整份测试是下策：一来防线没了，二来 93 行注释留在代码库里没人敢删。
 * 改签名后请记得 {@code mvn clean test}（增量编译不会重编测试类，会在运行期抛
 * {@code NoSuchMethodError}）。</p>
 */
class LlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 假 Key：真 Key 会产生费用，绝不该出现在单元测试里 */
    private static final String FAKE_KEY = "sk-fake-key-for-unit-test";

    @Test
    @DisplayName("配置齐全时会真正发起 HTTP 调用（用假 Key，预期拿到 401 而不是 NPE）")
    void sendsRealRequestWhenConfigured() {
        LlmClient client = newClient(FAKE_KEY, true);

        assertThatThrownBy(() -> client.chat(List.of(ChatMessage.user("你好"))))
                .isInstanceOf(BusinessException.class)
                // 401 说明：配置被读到了、URL 拼对了、请求到得了 DeepSeek。
                // 如果这里是 NPE 或"未配置 API Key"，说明注入链路又断了
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("Key 为空时给出明确提示，而不是空指针")
    void reportsMissingKeyClearly() {
        LlmClient client = newClient("", true);

        assertThatThrownBy(() -> client.chat(List.of(ChatMessage.user("你好"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未配置大模型 API Key");
    }

    @Test
    @DisplayName("开关关闭时不发起任何外部调用")
    void skipsEverythingWhenDisabled() {
        LlmClient client = newClient(FAKE_KEY, false);

        assertThatThrownBy(() -> client.chat(List.of(ChatMessage.user("你好"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    @DisplayName("消息为空时直接拦截，不做无意义的调用")
    void rejectsEmptyMessages() {
        LlmClient client = newClient(FAKE_KEY, true);

        assertThatThrownBy(() -> client.chat(List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("对话内容不能为空");
    }

    @Test
    @DisplayName("ChatMessage 序列化后必须是 OpenAI 协议的 role/content 结构")
    void chatMessageSerializesToOpenAiShape() throws Exception {
        // 这条断言的是"请求体长什么样"。它防止的是：有人给 ChatMessage 加字段
        // （比如加个 id、时间戳），结果整个请求被模型判为非法参数。
        // 协议的形状是外部契约，不是内部实现细节，值得钉死。
        String json = objectMapper.writeValueAsString(List.of(
                ChatMessage.system("你是商城助手"),
                ChatMessage.user("你好")));

        assertThat(json).isEqualTo(
                "[{\"role\":\"system\",\"content\":\"你是商城助手\"},"
                        + "{\"role\":\"user\",\"content\":\"你好\"}]");
    }

    private LlmClient newClient(String apiKey, boolean enabled) {
        AiProperties properties = new AiProperties();
        properties.setEnabled(enabled);
        properties.setBaseUrl("https://api.deepseek.com/chat/completions");
        properties.setModel("deepseek-v4-flash");
        properties.setApiKey(apiKey);
        properties.setTimeoutSeconds(30);
        return new LlmClient(properties, objectMapper);
    }
}
