package com.mall.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.io.Serializable;

/**
 * 订单 DTO：入参对象
 */
@Data
public class OrderDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    @NotNull(message = "商品 ID 不能为空")
    private Long productId;

    @NotNull(message = "购买数量不能为空")
    @Positive(message = "购买数量必须大于 0")
    private Integer quantity;
}
