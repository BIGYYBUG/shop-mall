package com.mall.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 设置购物车条目的勾选状态。
 *
 * <p><b>为什么是批量接口，而不是逐条改</b>：结算前"全选 / 全不选"是最常见的动作。
 * 若逐条调用，一辆 20 件商品的车就要发 20 个请求，在网络与 Redis 上都是纯浪费。</p>
 *
 * <p><b>productIds 为空表示整车</b>（即全选 / 取消全选）。把它和"指定若干商品"
 * 合并到一个接口，是因为两者的服务端行为完全一致 —— 只是目标集合不同。
 * 前端"全选"复选框直接传 {@code null} 即可，不必再拼一个包含所有 ID 的数组。</p>
 *
 * @param productIds 目标商品；为 null 或空集合时表示整车
 * @param selected   目标状态：true 勾选，false 取消勾选
 */
public record CartSelectDTO(

        List<Long> productIds,

        @NotNull(message = "勾选状态不能为空")
        Boolean selected
) {
}
