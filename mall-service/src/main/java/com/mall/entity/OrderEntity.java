package com.mall.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单主表实体，对应 {@code mall_order}。
 *
 * <h3>为什么继承 BaseEntity（逻辑删除），而购物车是物理删除</h3>
 *
 * <p>三条判据（与 {@code mall_cart_item} 对照）：</p>
 * <ol>
 *   <li><b>是否审计实体</b> —— 订单是交易凭证，必须可追溯 → 逻辑删除。
 *       购物车不是账，不是凭证。</li>
 *   <li><b>产品语义是不是"真删"</b> —— 用户从不说"删掉我的订单"，
 *       他想要的是"从我的列表里藏起来"，那是另一个语义（当前不做）。
 *       而购物车点删除就是要它消失。</li>
 *   <li><b>增长是否最快</b> —— 订单量不低，但远低于购物车/聊天的写频率，
 *       不构成否决项。</li>
 * </ol>
 *
 * <p>唯一键照抄项目约定：{@code uk_order_no (order_no, deleted)}，
 * 且 {@code deleted} 是 BIGINT（见 {@link BaseEntity#getDeleted()} 的三条约束）。</p>
 *
 * <h3>为什么不建状态流水表</h3>
 *
 * <p>{@code payTime / shipTime / finishTime / cancelTime} 四个时间戳 +
 * {@code cancelReason} 已经覆盖了当前需要的全部流转信息，零额外表成本。
 * 完整的操作流水（谁改的、备注、IP）是运营审计需求，当前不存在真实运营。
 * ⚠️ 将来若补流水表，它<b>必须跟主表一样逻辑删</b> —— 主表逻辑删、流水物理删
 * 会留下永久孤儿（项目已有此铁律）。</p>
 *
 * <h3>状态流转</h3>
 * <pre>
 *   0 待支付 ──支付──> 1 已支付 ──发货──> 2 已发货 ──确认收货──> 3 已完成
 *      │
 *      └──取消 / 超时关单 / 平台关闭──> 4 已取消（终态）
 * </pre>
 *
 * <p><b>状态机的执行权不在 Java 的 if-else 里，而在 SQL 的 {@code WHERE status = ?} 上</b>：
 * 见 {@code OrderMapper} 的四个 CAS 语句。任何"先查状态再判断再更新"的写法都有并发窗口。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_order")
public class OrderEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 订单号：对外唯一标识，{@code yyyyMMdd} + 24 位随机，共 32 位 */
    private String orderNo;

    /** 买家用户 ID，一律取自令牌上下文 */
    private Long userId;

    /** 商品总额 = Σ 明细 subtotal，服务端计算 */
    private BigDecimal totalAmount;

    /** 运费（本轮恒为 0，留字段） */
    private BigDecimal freightAmount;

    /** 优惠金额（本轮恒为 0，留字段） */
    private BigDecimal discountAmount;

    /** 应付金额 = totalAmount + freightAmount - discountAmount */
    private BigDecimal payAmount;

    /** 件数合计 = Σ quantity。明细创建后不可变，故该冗余安全 */
    private Integer itemCount;

    /** 状态：0 待支付，1 已支付，2 已发货，3 已完成，4 已取消 */
    private Integer status;

    /** 支付方式：1 模拟支付 */
    private Integer payType;

    /** 收货人姓名（下单时快照） */
    private String receiverName;

    /** 收货人手机号（下单时快照） */
    private String receiverPhone;

    /** 收货地址（下单时快照） */
    private String receiverAddress;

    /** 买家备注 */
    private String remark;

    /** 支付截止时间 = 下单时间 + 支付窗口。⚡ 冗余列，为了让关单任务能走索引 */
    private LocalDateTime closeDeadline;

    /** 支付时间 */
    private LocalDateTime payTime;

    /** 发货时间 */
    private LocalDateTime shipTime;

    /** 完成时间 */
    private LocalDateTime finishTime;

    /** 取消 / 关闭时间 */
    private LocalDateTime cancelTime;

    /** 取消原因：买家取消 / 超时未支付 / 平台关闭 */
    private String cancelReason;
}
