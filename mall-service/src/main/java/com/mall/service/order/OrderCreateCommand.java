package com.mall.service.order;

import java.util.List;

/**
 * 下单指令 —— 订单域内部的入参载体。
 *
 * <h3>为什么要有这个内部对象</h3>
 *
 * <p>下单有两个外部入口（购物车结算、直接购买），它们最终会汇入同一个落库流程。
 * 让落库层直接接收 {@code OrderCheckoutDTO} 或 {@code OrderBuyNowDTO} 都不对：
 * 前者没有商品信息，后者只有一个商品。于是需要一个<b>把两种入口归一化之后的形状</b>——
 * 这就是本对象存在的全部理由。</p>
 *
 * <p>它与 API 层 DTO 的区别：DTO 描述的是"调用方传了什么"，
 * 本对象描述的是"业务已经确定了什么"——商品行已经查过、校验过，
 * 金额已经算好。落库层不再做任何取值判断。</p>
 *
 * @param userId            买家用户 ID，取自令牌，绝不来自请求体
 * @param lines             待购买的（商品, 数量）行；调用方需保证 productId 已去重
 * @param receiverName      收货人姓名
 * @param receiverPhone     收货人手机号
 * @param receiverAddress   收货地址
 * @param remark            买家备注
 * @param payTimeoutMinutes 支付窗口（分钟），决定 closeDeadline
 */
public record OrderCreateCommand(
        Long userId,
        List<Line> lines,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        String remark,
        int payTimeoutMinutes
) {

    /**
     * 下单行。
     *
     * <p><b>刻意不带商品名与单价</b>：那些必须从数据库读出来的"事实"，
     * 由落库层在事务内查商品表获得。如果让调用方把价格一起传进来，
     * 就等于把定价权交给了调用方 —— 即使今天的调用方是自己人，
     * 这个口子迟早会被某个新入口用错。</p>
     */
    public record Line(Long productId, Integer quantity) {
    }
}
