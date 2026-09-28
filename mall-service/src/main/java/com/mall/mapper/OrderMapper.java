package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.OrderEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 订单 Mapper。
 *
 * <h3>为什么状态流转必须手写 SQL，而不是用 Wrapper</h3>
 *
 * <p>状态流转的正确性完全依赖「<b>把期望的前置状态写进 {@code WHERE}</b>」，
 * 再用 affected rows 是否为 1 判定成败。这就是数据库层面的 CAS。
 * 用 Wrapper 拼 {@code eq(status, from)} 虽然也能实现，但读者一眼看不出
 * 「这里在做并发控制」，容易被后人"顺手简化"成先查再改 —— 那正是超卖、
 * 重复回补库存这类事故的源头。手写 XML 能把这段意图连同注释一起钉在代码里。</p>
 *
 * <p>影响行数的语义（三个方法都适用）：</p>
 * <ul>
 *   <li>{@code 1} —— 本次流转成功；</li>
 *   <li>{@code 0} —— 状态已被别人改过（并发）或本就非法（如已发货还想取消）。</li>
 * </ul>
 */
public interface OrderMapper extends BaseMapper<OrderEntity> {

    /**
     * 待支付 → 已支付（同时写入支付方式与支付时间）。
     *
     * @return 1 = 成功；0 = 状态已不是待支付（重复支付 / 已被取消或超时关闭）
     */
    int casPay(@Param("id") Long id, @Param("payType") Integer payType);

    /**
     * 已支付 → 已发货。
     *
     * @return 1 = 成功；0 = 状态已不是已支付
     */
    int casShip(@Param("id") Long id);

    /**
     * 已发货 → 已完成（买家确认收货）。
     *
     * @return 1 = 成功；0 = 状态已不是已发货
     */
    int casConfirm(@Param("id") Long id);

    /**
     * 待支付 → 已取消（买家取消 / 超时关单 / 平台关闭共用）。
     *
     * <p>三种取消来源共用同一个 CAS 语句，只是 {@code reason} 不同。
     * 这样「只能取消待支付订单」这条规则就只有一处实现，不会因为
     * 加了新的取消入口而漏掉校验。</p>
     *
     * <p>⚠️ 调用方必须<b>先执行本方法并确认返回 1，再去回补库存</b>。
     * 顺序反了就会出现：两次取消请求都先回补库存，只有一次 CAS 成功 ——
     * 库存凭空多出来。</p>
     *
     * @return 1 = 成功关闭；0 = 状态已不是待支付（已被支付、已取消或已被并发关掉）
     */
    int casCancel(@Param("id") Long id, @Param("reason") String reason);

    /**
     * 捞出已超时未支付的订单（关单任务用）。
     *
     * <p>时间比较用的是数据库的 {@code NOW()} 而不是应用传入的时间戳 ——
     * 多实例部署时各机器时钟可能有偏差，把"是否超时"的判定权交给同一个时钟最稳。</p>
     *
     * <p>校验条件写成 {@code close_deadline <= NOW()}（常量与列比较）而不是
     * {@code create_time < NOW() - INTERVAL 30 MINUTE}（函数作用在列上）——
     * 后者会让 {@code idx_status_deadline} 直接失效、退化成全表扫描。</p>
     *
     * @param limit 单轮最多处理多少单，避免一次捞太多拖长任务
     */
    List<OrderEntity> selectTimeoutCandidates(@Param("limit") int limit);
}
