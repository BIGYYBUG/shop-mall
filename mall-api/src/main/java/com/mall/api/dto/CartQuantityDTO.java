package com.mall.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 修改购物车中某商品的数量。
 *
 * <p>语义是<b>设为</b>该值，不是累加 —— 加购才累加（见 {@link CartAddDTO}）。
 * 前端数量输入框每次改动调一次这里。</p>
 *
 * @param quantity 目标数量
 */
public record CartQuantityDTO(

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量至少为 1")
        @Max(value = 200, message = "单个商品最多 200 件")
        Integer quantity
) {
}
