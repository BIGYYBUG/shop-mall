package com.mall.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

@Data
public class ChatDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 会话标识。
     *
     * <p><b>首轮不传</b>（服务端新建后随响应返回），<b>后续轮次必须原样带回</b>。</p>
     *
     * <p>注意它<b>不能加</b> {@code @NotBlank}：首轮对话时客户端还不存在这个值，
     * 强制必填会让「开始新对话」这个动作本身无法发起 —— 而且是 400，不是业务提示。</p>
     *
     * <p>不传的后果不是报错，而是每轮都开一个新会话 —— 表现为「模型失忆」。
     * 这类 bug 不抛异常，只能靠对话体验发现，所以 {@code ChatVO} 必须把它回传。</p>
     */
    @Size(max = 64, message = "会话标识长度非法")
    private String conversationId;

    /**
     * 用户输入的自然语言问题
     */
    @NotBlank(message = "消息不能为空")
    @Size(max = 2000, message = "单条消息不能超过 2000 字")
    private String message;
}
