package com.mall.controller;

import com.mall.api.vo.OrderVO;
import com.mall.api.vo.PageVO;
import com.mall.common.annotation.RequiresPermission;
import com.mall.common.result.Result;
import com.mall.service.order.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 订单管理。
 *
 * <p>与 {@link AdminProductController} 保持同一套约定：路径统一挂 {@code /admin} 前缀，
 * 每个方法显式声明所需权限点，校验逻辑全在 {@code PermissionAspect} 里。</p>
 *
 * <h3>为什么平台侧有权限码，买家侧一条都没有</h3>
 *
 * <p>买家只能看/动自己的订单，"这是谁的订单"由令牌钉死，RBAC 帮不上忙（见
 * {@link OrderController}）。平台侧不同：运营可以看到并操作<b>所有人</b>的订单，
 * 这是真正需要授权的动作，所以有 {@code order:list / detail / ship / close}
 * 四个权限码。</p>
 *
 * <h3>为什么发货与关闭是两个独立权限点</h3>
 *
 * <p>{@code order:ship} 是"把货发出去"，{@code order:close} 是"把单砍掉"。
 * 这两件事在真实团队里通常是不同角色：发货是仓库/运营，关单往往要主管确认
 * （关掉意味着这单不做了）。合成一个"order:manage"就再也拆不开了 ——
 * 与 {@code product:update} / {@code product:status} 的拆分同理。</p>
 */
@RestController
@RequestMapping("/admin/order")
@RequiredArgsConstructor
public class AdminOrderController {

    private final OrderService orderService;

    /**
     * 订单分页（平台视角，可见全部用户）。
     *
     * @param status  可选：按状态过滤
     * @param orderNo 可选：按订单号模糊查询（运营通常只记得后几位）
     * @param userId  可选：按买家过滤
     */
    @GetMapping("/page")
    @RequiresPermission("order:list")
    public Result<PageVO<OrderVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                        @RequestParam(defaultValue = "10") long pageSize,
                                        @RequestParam(required = false) Integer status,
                                        @RequestParam(required = false) String orderNo,
                                        @RequestParam(required = false) Long userId) {
        return Result.success(orderService.pageAdminOrders(pageNum, pageSize, status, orderNo, userId));
    }

    /**
     * 订单详情（含明细，不限归属）。
     *
     * <p>与买家侧的关键差别：这里<b>不做归属校验</b> —— 有权看订单的运营本来就该
     * 看到任意一笔订单。归属保护是买家侧的事，用权限码保护平台侧。</p>
     */
    @GetMapping("/{orderNo}")
    @RequiresPermission("order:detail")
    public Result<OrderVO> detail(@PathVariable("orderNo") String orderNo) {
        return Result.success(orderService.getOrderForAdmin(orderNo));
    }

    /**
     * 发货（仅已支付订单）。
     *
     * <p>状态不对时返回 409，由 SQL 的 {@code WHERE status = 1} 兜住 ——
     * 包括"未支付的单被发货""已取消的单被发货"这些非法流转。</p>
     */
    @PostMapping("/{orderNo}/ship")
    @RequiresPermission("order:ship")
    public Result<OrderVO> ship(@PathVariable("orderNo") String orderNo) {
        return Result.success("已发货", orderService.ship(orderNo));
    }

    /**
     * 平台强制关闭订单（仅待支付）。
     *
     * <p>已支付的订单不能在这里关闭 —— 那需要退款流程，本轮不存在。
     * 想关掉已支付的单，正确做法是先接入退款，而不是放宽这里的条件。</p>
     */
    @PostMapping("/{orderNo}/close")
    @RequiresPermission("order:close")
    public Result<OrderVO> close(@PathVariable("orderNo") String orderNo) {
        return Result.success("订单已关闭", orderService.close(orderNo));
    }
}
