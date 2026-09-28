package com.mall.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 购物车结算下单参数。
 *
 * <p><b>结算哪些商品不由请求决定</b>：只结算"当前用户购物车里已勾选"的条目。
 * 因此本 DTO 里没有任何商品标识 —— 如果让前端传 productIds，
 * 就等于把"买什么"交给了客户端，绕过勾选状态不说，还给篡改留了口子。</p>
 *
 * <p><b>为什么没有 userId</b>：买家只能是令牌里的那个人。契约里不存在这个字段，
 * 比"服务端记得忽略它"更可靠（IDOR 的标准防线）。</p>
 *
 * <p><b>为什么没有金额字段</b>：金额一律服务端计算。契约里出现金额，
 * 意味着客户端有机会影响定价 —— 1 分钱下单的经典漏洞就是这么来的。</p>
 *
 * <p><b>为什么收货字段在两个 DTO 里重复声明</b>：Java 的 record 不能继承，
 * 改成普通类 + 父类会把校验注解拆到两个文件里，读代码时要在两个地方拼起来才看得懂。
 * 宁可重复 4 行声明，也不要为了省这几行而牺牲可读性。</p>
 *
 * @param receiverName    收货人姓名
 * @param receiverPhone   收货人手机号
 * @param receiverAddress 收货地址
 * @param remark          买家备注，可空
 */
public record OrderCheckoutDTO(

        @NotBlank(message = "收货人姓名不能为空")
        @Size(max = 32, message = "收货人姓名最多 32 个字符")
        String receiverName,

        @NotBlank(message = "收货人手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String receiverPhone,

        @NotBlank(message = "收货地址不能为空")
        @Size(max = 255, message = "收货地址最多 255 个字符")
        String receiverAddress,

        @Size(max = 255, message = "备注最多 255 个字符")
        String remark
) {
}
