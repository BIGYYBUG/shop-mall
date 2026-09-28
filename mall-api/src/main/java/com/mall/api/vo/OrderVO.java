package com.mall.api.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单 VO。
 *
 * <h3>对外一律用 {@code orderNo}，不用自增 {@code id}</h3>
 *
 * <p>自增 id 可被枚举：{@code /order/1001}、{@code /order/1002} 能扫出全站订单规模，
 * 也方便别人试探越权。{@code id} 保留在 VO 里只因为前端偶尔需要它做 key，
 * <b>所有接口路径都以 {@code orderNo} 为准</b>。</p>
 *
 * <h3>为什么金额有四个而不是一个</h3>
 *
 * <p>{@code totalAmount}（商品总额）、{@code freightAmount}（运费）、
 * {@code discountAmount}（优惠）、{@code payAmount}（应付）四者是
 * <b>恒等式关系</b>：{@code payAmount = totalAmount + freightAmount - discountAmount}。
 * 拆开存是为了让"为什么实付和商品总额不一样"这件事在数据结构上自解释。
 * 本轮运费与优惠恒为 0，字段先留着 —— 加字段的成本远低于改口径。</p>
 *
 * <h3>状态与时间戳</h3>
 *
 * <p>项目没有单独的状态流水表，{@code payTime / shipTime / finishTime / cancelTime}
 * 四个时间戳就承担了"流转历史"的职责。{@code statusText} 由服务端翻译，
 * 避免每个前端各写一份状态文案映射（多端时必然对不齐）。</p>
 */
@Data
public class OrderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增主键（前端做列表 key 用；接口路径一律用 orderNo） */
    private Long id;

    /** 订单号，对外唯一标识 */
    private String orderNo;

    /** 买家用户 ID */
    private Long userId;

    /** 状态：0 待支付，1 已支付，2 已发货，3 已完成，4 已取消 */
    private Integer status;

    /** 状态文案，由服务端翻译 */
    private String statusText;

    /** 商品总额 = Σ 明细 subtotal */
    private BigDecimal totalAmount;

    /** 运费 */
    private BigDecimal freightAmount;

    /** 优惠金额 */
    private BigDecimal discountAmount;

    /** 应付金额 = totalAmount + freightAmount - discountAmount */
    private BigDecimal payAmount;

    /** 件数合计 = Σ quantity */
    private Integer itemCount;

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

    /** 支付截止时间 */
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

    private LocalDateTime createTime;

    /**
     * 订单明细。
     *
     * <p>列表接口为 {@code null}（不回明细，避免一页 10 条订单查出几百行明细）；
     * 详情接口与所有写操作返回时才填充。<b>同一个 VO 承载两种粒度是被允许的，
     * 但必须在文档里说清哪个接口给哪个粒度</b> —— 否则前端会写出"列表页也读 items"的代码。</p>
     */
    private List<OrderItemVO> items;
}
