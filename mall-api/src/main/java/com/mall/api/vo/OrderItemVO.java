package com.mall.api.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单明细 VO —— 商品与价格的<b>快照</b>。
 *
 * <p><b>为什么这里的商品名/图/价不来自商品表</b>：商品会改价、改名、换图、下架、被删。
 * 如果明细只存 {@code productId}、展示时 join 商品表，那么"昨天 199 元买的订单，
 * 今天显示成 299 元"，对账与售后会全线崩塌。订单一旦生成就与商品<b>解耦</b>，
 * {@code productId} 只用于跳转和售后定位。</p>
 *
 * <p>{@code price} 与 {@code subtotal} 都由服务端在下单那一刻算好并落库，
 * 出参直接读库，不做二次计算 —— 任何"重新算一遍"的想法都会给历史订单引入漂移。</p>
 */
@Data
public class OrderItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品 ID（弱关联，用于跳转商品页） */
    private Long productId;

    /** 卖家用户 ID 快照：0 = 平台自营 */
    private Long sellerId;

    /** 商品名称快照 */
    private String productName;

    /** 封面图可访问地址（由 objectKey 拼出，绝不返回裸 key） */
    private String productCoverUrl;

    /** 成交单价快照 */
    private BigDecimal price;

    /** 购买数量 */
    private Integer quantity;

    /** 小计 = price × quantity，下单时由服务端计算并落库 */
    private BigDecimal subtotal;
}
