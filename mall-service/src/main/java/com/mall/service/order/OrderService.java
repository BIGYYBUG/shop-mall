package com.mall.service.order;

import com.mall.api.dto.OrderBuyNowDTO;
import com.mall.api.dto.OrderCheckoutDTO;
import com.mall.api.vo.OrderVO;
import com.mall.api.vo.PageVO;

/**
 * 订单服务。
 *
 * <h3>两类接口的分界线</h3>
 *
 * <ul>
 *   <li><b>买家侧（没有 Admin 前缀的方法）</b> —— 归属由令牌钉死：
 *       每个方法都收 {@code userId}，且都来自 {@code UserContext}。
 *       这些方法<b>不挂任何权限码</b>，因为"只能操作自己那笔订单"不是 RBAC
 *       能表达的（RBAC 只管"能不能调这个接口"）。</li>
 *   <li><b>平台侧（Admin 后缀的方法）</b> —— 由 Controller 上的
 *       {@code @RequiresPermission("order:*")} 授权，服务层不再重复判权限。</li>
 * </ul>
 *
 * <h3>归属不符一律返回 404，不是 403</h3>
 *
 * <p>403 的语义是"这东西存在，但你没资格"。对订单而言，这等于告诉攻击者
 * "这个单号是真实存在的"，可以据此枚举全站订单量。404 才是正确的沉默。</p>
 *
 * <h3>所有写操作都返回完整 OrderVO</h3>
 *
 * <p>沿用购物车的约定：一次请求就把最新状态与明细交回前端，避免"改完再查一次"
 * 的两次往返，也避免两次请求之间状态被别的动作改掉而产生的界面不一致。</p>
 */
public interface OrderService {

    // ==================================================================
    // 买家侧
    // ==================================================================

    /**
     * 从购物车结算下单（只结算已勾选且可购买的条目）。
     *
     * @param userId 买家用户 ID，取自令牌
     */
    OrderVO placeOrderFromCart(Long userId, OrderCheckoutDTO dto);

    /**
     * 直接购买下单（不经过购物车，也不在购物车里留痕）。
     */
    OrderVO placeOrderBuyNow(Long userId, OrderBuyNowDTO dto);

    /**
     * 我的订单分页。
     *
     * <p>返回的 {@code OrderVO} <b>不含明细</b>（列表只需单号/状态/金额/件数），
     * 件数直接取订单主表上的冗余列，不需要 join 明细表。</p>
     *
     * @param status 可选：按状态过滤，null 表示全部
     */
    PageVO<OrderVO> pageMyOrders(Long userId, Integer status, long pageNum, long pageSize);

    /** 我的订单详情（含明细）。归属不符返回 404。 */
    OrderVO getMyOrder(Long userId, String orderNo);

    /** 模拟支付。订单不是待支付时返回业务错误。 */
    OrderVO pay(Long userId, String orderNo);

    /** 买家取消（仅待支付）。 */
    OrderVO cancel(Long userId, String orderNo);

    /** 确认收货（仅已发货）。 */
    OrderVO confirm(Long userId, String orderNo);

    // ==================================================================
    // 平台侧
    // ==================================================================

    /**
     * 订单分页（平台视角，可见全部用户）。
     *
     * @param status  可选：按状态过滤
     * @param orderNo 可选：按订单号模糊查询
     * @param userId  可选：按买家过滤
     */
    PageVO<OrderVO> pageAdminOrders(long pageNum, long pageSize,
                                    Integer status, String orderNo, Long userId);

    /** 订单详情（平台视角，含明细，不限归属）。 */
    OrderVO getOrderForAdmin(String orderNo);

    /** 发货（仅已支付）。 */
    OrderVO ship(String orderNo);

    /** 平台强制关闭（仅待支付）。 */
    OrderVO close(String orderNo);
}
