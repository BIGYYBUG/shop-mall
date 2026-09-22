package com.mall.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 加入购物车参数。
 *
 * <p><b>为什么没有 userId</b>：购物车属于谁，只能由令牌决定。一旦契约里出现
 * {@code userId}，调用方就能传别人的 id 往别人车里塞东西 —— 典型的 IDOR。
 * 契约里根本不存在这个字段，比"服务端记得忽略它"更可靠。</p>
 *
 * @param productId 商品 ID
 * @param quantity  本次加入的数量（会累加到已有数量上，而不是覆盖）
 */
public record CartAddDTO(

        @NotNull(message = "商品 ID 不能为空")
        @Min(value = 1, message = "商品 ID 不合法")
        Long productId,

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量至少为 1")
        @Max(value = 200, message = "单个商品最多 200 件")
        Integer quantity
) {
}
