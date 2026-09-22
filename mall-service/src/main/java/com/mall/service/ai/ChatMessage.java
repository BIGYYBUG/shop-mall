package com.mall.service.ai;

/**
 * 一条对话消息，对应 OpenAI 协议 messages 数组的一项。
 *
 * <p>role 只有三种取值：
 * <ul>
 *   <li><b>system</b>：设定人设与规则，放最前面</li>
 *   <li><b>user</b>：用户说的话</li>
 *   <li><b>assistant</b>：模型此前说过的话（拼多轮历史时用）</li>
 * </ul>
 *
 * <p><b>关键认知：服务端是无状态的</b>，它不记得上一轮发生了什么。
 * 「多轮对话」必须由我们自己把历史拼进 messages 再发过去 —— 这也意味着
 * 历史越长，输入 token 越多，每一轮都更贵。</p>
 *
 * <p>另外：system 与 user 在模型眼里<b>不是权限区别</b>，只是位置与措辞的区别。
 * 所以绝不要把 system 提示词当成安全边界，真正的权限必须写在 Java 代码里。</p>
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}
