package com.mall.api.vo;

import lombok.Data;

import java.io.Serializable;

/** AI 对话响应。把 token 用量一并返回，让成本在开发阶段就可见 */
@Data
public class ChatVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 本轮所属会话标识。
     *
     * <p><b>必须回传</b>：首轮时它由服务端新建，客户端拿不到就无处可取，
     * 结果是下一轮又开一个新会话 —— 表现为「模型失忆」，且不报任何错。</p>
     */
    private String conversationId;

    /** 模型回答正文 */
    private String reply;

    /** 输入 token（本轮发过去的全部内容） */
    private Integer promptTokens;

    /** 输出 token（模型生成的） */
    private Integer completionTokens;

    /** 合计 */
    private Integer totalTokens;

    /** 耗时（毫秒） */
    private Long costMs;
}
