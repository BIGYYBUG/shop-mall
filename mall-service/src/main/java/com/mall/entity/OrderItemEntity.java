package com.mall.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 订单明细实体，对应 {@code mall_order_item}。
 *
 * <h3>为什么名称 / 图片 / 单价都是"快照"而不是外键引用</h3>
 *
 * <p>商品会改价、改名、换图、下架、被逻辑删除。若明细只存 {@code productId}、
 * 展示时 join 商品表，那么用户昨天 199 元下的单，今天会显示成 299 元 ——
 * 对账、售后、纠纷处理全部失真。</p>
 *
 * <p>订单一旦生成，就与商品<b>解耦</b>。{@code productId} 只是弱关联，
 * 用于跳转商品页和售后定位，不承担任何展示职责。</p>
 *
 * <h3>为什么继承 BaseEntity</h3>
 *
 * <p>主表是逻辑删除，明细<b>必须同样逻辑删除</b>。项目铁律：
 * 绝不允许「一张逻辑删、一张物理删」——主表一旦恢复，明细就成了孤儿，
 * 订单会显示成"有订单没内容"。</p>
 *
 * <h3>{@code sellerId} 为什么现在就留</h3>
 *
 * <p>一张订单可能包含多个卖家的商品，完整做法是「父订单 + 按卖家拆分的子订单」，
 * 那样每个卖家才能独立发货 —— 那是独立的一大块工作，不在本轮范围。
 * 但快照 {@code sellerId} 的成本≈0，将来做拆单可以直接用，
 * <b>不需要回填历史数据</b>；反之若现在不留，历史订单永远补不回来。</p>
 *
 * <p>原则：<b>先把能确定的字段留出来，再推迟不确定的结构。</b></p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_order_item")
public class OrderItemEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 订单 ID（弱关联，不加外键） */
    private Long orderId;

    /** 商品 ID（弱关联，不加外键） */
    private Long productId;

    /** 卖家用户 ID 快照：0 = 平台自营 */
    private Long sellerId;

    /** 商品名称快照 */
    private String productName;

    /** 封面图 objectKey 快照（存 key 不存 URL，出参时由 storage 拼） */
    private String productCoverKey;

    /** 成交单价快照 */
    private BigDecimal price;

    /** 购买数量 */
    private Integer quantity;

    /** 小计 = price × quantity，服务端计算，不接受前端传入 */
    private BigDecimal subtotal;
}
