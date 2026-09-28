package com.mall.service.order;

/**
 * 订单状态字典。
 *
 * <h3>状态机</h3>
 * <pre>
 *   0 待支付 ──支付──&gt; 1 已支付 ──发货──&gt; 2 已发货 ──确认收货──&gt; 3 已完成
 *      │
 *      └──取消 / 超时关单 / 平台关闭──&gt; 4 已取消（终态）
 * </pre>
 *
 * <h3>合法流转的执行权在哪里 —— 不在这个枚举里</h3>
 *
 * <p><b>本枚举只负责"码 → 文案"的翻译，不负责校验流转是否合法。</b>
 * 合法性由 {@code OrderMapper} 的四个 CAS 语句用 {@code WHERE status = 期望值} 强制：
 * 每条语句的 SQL 里已经把"能从哪个状态转到哪个状态"写死了。</p>
 *
 * <p>把规则放在 SQL 里而不是这里，是为了避免<b>两个真相来源</b>：
 * 如果枚举里也写一份 {@code canTransitionTo(from, to)}，那么一旦有人新增了一条
 * SQL 却忘了改枚举（或反过来），系统就会出现"Java 说可以、数据库说不行"的
 * 隐性不一致 —— 而这类不一致在压测和并发下才会暴露。状态机只该有一个权威实现。</p>
 *
 * <h3>为什么不额外建状态流水表</h3>
 * <p>订单主表上的 {@code pay_time / ship_time / finish_time / cancel_time} 四个时间戳
 * 已经覆盖当前需要的全部流转信息。见 {@code OrderEntity} 的说明。</p>
 */
public enum OrderStatus {

    /** 待支付：下单成功，等待付款；超时会被自动关闭 */
    PENDING(0, "待支付"),

    /** 已支付：付款完成，等待发货。销量在这一步累加 */
    PAID(1, "已支付"),

    /** 已发货：平台已发出，等待买家确认收货 */
    SHIPPED(2, "已发货"),

    /** 已完成：买家确认收货，终态 */
    FINISHED(3, "已完成"),

    /** 已取消：买家取消 / 超时未支付 / 平台关闭，终态 */
    CANCELED(4, "已取消");

    private final int code;
    private final String text;

    OrderStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public int code() {
        return code;
    }

    public String text() {
        return text;
    }

    /**
     * 把状态码翻译成文案。
     *
     * <p><b>为什么由服务端翻译而不是前端</b>：状态文案散到各个前端后，
     * 一旦新增状态（比如将来加"已退款"），必然出现"App 改了、小程序没改"，
     * 用户在不同端看到不同说法。放进出参，只有一处需要维护。</p>
     *
     * @param code 状态码，可为 null
     * @return 文案；未知码返回 {@code "未知状态(n)"} 而不是 null —— 让前端不必判空
     */
    public static String text(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status.text;
            }
        }
        return "未知状态(" + code + ")";
    }
}
