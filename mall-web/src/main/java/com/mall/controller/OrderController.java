package com.mall.controller;

import com.mall.api.dto.OrderBuyNowDTO;
import com.mall.api.dto.OrderCheckoutDTO;
import com.mall.api.vo.OrderVO;
import com.mall.api.vo.PageVO;
import com.mall.common.context.UserContext;
import com.mall.common.result.Result;
import com.mall.service.order.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 买家订单接口。
 *
 * <h3>权限：全部只要"登录"，一个 {@code @RequiresPermission} 都没有</h3>
 *
 * <p>与 {@link CartController} 完全同理：订单是"我自己的东西"。
 * RBAC 只能回答"能不能调这个接口"，回答不了"能操作哪一笔订单" ——
 * 后者靠 {@code userId} 一律取自 {@link UserContext}（令牌），
 * 请求里根本不传 userId，再由服务层做归属校验。</p>
 *
 * <p>这也是为什么 {@code /order/**} 在权限字典里<b>没有对应的买家权限码</b>：
 * USER 角色名下继续是 0 条权限，这是刻意的设计，不是漏配。</p>
 *
 * <h3>归属不符返回 404，不是 403</h3>
 *
 * <p>403 等于告诉攻击者"这个单号是真实存在的，只是不是你的"，可据此枚举全站订单量。
 * 对不属于自己的资源，"不存在"才是正确的回答。这条规则实现在
 * {@code OrderServiceImpl#requireMyOrder}。</p>
 *
 * <h3>写操作都返回完整 {@link OrderVO}（含明细）</h3>
 *
 * <p>沿用购物车的约定：一次请求就把最新状态与明细交回前端。
 * 用户点完"支付"无需再发一次 GET，状态、时间戳、明细一轮到位。</p>
 */
@RestController
@RequestMapping("/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // ==================================================================
    // 下单
    // ==================================================================

    /**
     * 购物车结算下单。
     *
     * <p>结算哪些商品<b>不由请求决定</b>：只结算当前用户购物车里"已勾选且可购买"的条目。
     * 因此 {@link OrderCheckoutDTO} 里没有任何商品标识，也不含金额。</p>
     */
    @PostMapping("/checkout")
    public Result<OrderVO> checkout(@Valid @RequestBody OrderCheckoutDTO dto) {
        return Result.success("下单成功", orderService.placeOrderFromCart(UserContext.getUserId(), dto));
    }

    /**
     * 直接购买下单（不经过购物车）。
     *
     * <p>与结算的差别只在"商品从哪来"：一个取购物车已勾选项，一个取请求里的商品。
     * 直接购买不会往购物车里插任何行。</p>
     */
    @PostMapping("/buy-now")
    public Result<OrderVO> buyNow(@Valid @RequestBody OrderBuyNowDTO dto) {
        return Result.success("下单成功", orderService.placeOrderBuyNow(UserContext.getUserId(), dto));
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 我的订单分页。
     *
     * <p>返回的记录<b>不含明细</b>（列表只需单号/状态/金额/件数），
     * 件数取订单主表上的冗余列 {@code item_count}，不需要 join 明细表。</p>
     *
     * @param status 可选：按状态过滤（0 待支付 / 1 已支付 / 2 已发货 / 3 已完成 / 4 已取消），
     *               不传表示全部
     */
    @GetMapping("/list")
    public Result<PageVO<OrderVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                        @RequestParam(defaultValue = "10") long pageSize,
                                        @RequestParam(required = false) Integer status) {
        return Result.success(
                orderService.pageMyOrders(UserContext.getUserId(), status, pageNum, pageSize));
    }

    /** 我的订单详情（含明细）。归属不符返回 404。 */
    @GetMapping("/{orderNo}")
    public Result<OrderVO> detail(@PathVariable("orderNo") String orderNo) {
        return Result.success(orderService.getMyOrder(UserContext.getUserId(), orderNo));
    }

    // ==================================================================
    // 状态流转（买家可触发）
    // ==================================================================

    /**
     * 模拟支付。
     *
     * <p>订单不是待支付时返回 409 —— 409 的语义是"请求本身没错，
     * 但你当前的状态不允许这个操作"，比 400（参数错）更准确。
     * 重复支付、支付已取消的订单都会落到这里，由 SQL 的
     * {@code WHERE status = 0} 兜住。</p>
     */
    @PostMapping("/{orderNo}/pay")
    public Result<OrderVO> pay(@PathVariable("orderNo") String orderNo) {
        return Result.success("支付成功", orderService.pay(UserContext.getUserId(), orderNo));
    }

    /** 买家取消订单（仅待支付）。已支付的订单不能取消 —— 本轮无退款流程。 */
    @PostMapping("/{orderNo}/cancel")
    public Result<OrderVO> cancel(@PathVariable("orderNo") String orderNo) {
        return Result.success("订单已取消", orderService.cancel(UserContext.getUserId(), orderNo));
    }

    /** 确认收货（仅已发货）。 */
    @PostMapping("/{orderNo}/confirm")
    public Result<OrderVO> confirm(@PathVariable("orderNo") String orderNo) {
        return Result.success("已确认收货", orderService.confirm(UserContext.getUserId(), orderNo));
    }
}
