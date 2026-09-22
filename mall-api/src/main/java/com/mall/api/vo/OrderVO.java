package com.mall.api.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单 VO：出参对象
 */
@Data
public class OrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long userId;

    private Long productId;

    private Integer quantity;

    private BigDecimal totalAmount;

    /** 订单状态：0-待支付 1-已支付 2-已发货 3-已完成 4-已取消 */
    private Integer status;

    private LocalDateTime createTime;
}
