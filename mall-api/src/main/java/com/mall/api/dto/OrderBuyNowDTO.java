package com.mall.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 直接购买（不经过购物车）参数。
 *
 * <p><b>为什么要有这条入口</b>：电商的"立即购买"与"加入购物车再结算"是两条真实路径。
 * 前者不能去购物车里插一行再删掉 —— 那会污染用户的购物车数据，
 * 且并发下还会和真正的加购互相干扰。</p>
 *
 * <p>与 {@link OrderCheckoutDTO} 的关系：两者最后会汇入同一个下单方法，
 * 差别只在"商品从哪来"——一个取购物车已勾选项，一个取请求里的商品。</p>
 *
 * @param productId       商品 ID
 * @param quantity        购买数量
 * @param receiverName    收货人姓名
 * @param receiverPhone   收货人手机号
 * @param receiverAddress 收货地址
 * @param remark          买家备注，可空
 */
public record OrderBuyNowDTO(

        @NotNull(message = "商品 ID 不能为空")
        @Min(value = 1, message = "商品 ID 不合法")
        Long productId,

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量至少为 1")
        @Max(value = 200, message = "单个商品最多 200 件")
        Integer quantity,

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
