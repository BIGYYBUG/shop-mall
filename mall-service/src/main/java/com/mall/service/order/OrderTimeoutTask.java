package com.mall.service.order;

import com.mall.entity.OrderEntity;
import com.mall.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 超时未支付订单的自动关单任务。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>下单会扣库存。如果买家一直不付款、也不主动取消，这批库存就被永久占住，
 * 别人想买也买不到。所以"支付窗口过期 → 自动关单 + 回补库存"是下单流程
 * 必须配对的另一半。</p>
 *
 * <h3>⚠️ 本任务最容易写错的地方：一单一事务，而不是一批一事务</h3>
 *
 * <p>看代码会发现这里是<b>在循环里逐单调</b> {@link OrderPersistenceService#cancel}，
 * 而不是"把一批 id 传进去、在服务端一个事务里全改完"。这不是偷懒或疏忽，
 * 是刻意的：</p>
 *
 * <ol>
 *   <li><b>长事务持锁</b> —— 一批 200 单里只要有一单走得慢（比如商品行正好被
 *       一个下单请求锁着），这个事务持有的所有行锁都不会释放，并发的下单/支付
 *       全部被拖住。逐单调则每单的持锁窗口只覆盖它自己那几行。</li>
 *   <li><b>失败隔离</b> —— 一次性事务里，第 137 单抛异常会让前 136 单的关单
 *       一起回滚。而"这批关单里有一单失败了"不该导致"这轮谁也没关成"。
 *       逐单事务下，失败的那单下轮重试，其余照常完成。</li>
 * </ol>
 *
 * <p>换句话说：<b>事务边界要对齐"业务上独立的最小单元"（一单），
 * 而不是对齐"这次循环处理的量"。</b></p>
 *
 * <h3>为什么每单还要再判一次（CAS）</h3>
 *
 * <p>从"扫描出候选"到"真正关闭"之间有时间窗口。这个窗口里买家可能刚好付了款、
 * 或自己点了取消。真正决定"这一单到底关不关得掉"的，是
 * {@code OrderPersistenceService.cancel} 里那条 {@code WHERE status = 0} 的 CAS ——
 * 本任务只负责"捞出来试一试"，抢不到（affected = 0）就静默跳过。
 * 扫描条件只是<b>候选项</b>，不是<b>判定结果</b>。</p>
 *
 * <h3>已知取舍</h3>
 *
 * <p>多实例部署时，两个实例可能同时扫描到同一批候选。它们会各自尝试 CAS ——
 * 只有一个成功，另一个拿到 affected = 0 跳过。结果正确，只是有一点重复劳动。
 * 要彻底避免需要分布式锁或 MQ 延迟消息，留待阶段四。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutTask {

    /**
     * 单轮最多处理的订单数。
     *
     * <p>加这个上限是为了防止"某次故障积压了几十万单、恢复后一轮全捞出来"
     * 把内存和数据库打满。宁可多跑几轮，也不要一次吞下全部。</p>
     */
    private static final int BATCH_LIMIT = 200;

    private final OrderMapper orderMapper;
    private final OrderPersistenceService orderPersistenceService;

    /**
     * 周期性关闭已超时未支付的订单。
     *
     * <p>用 {@code fixedDelay}（上一轮结束后再等 N 毫秒）而不是 {@code fixedRate}：
     * 处理耗时不可控，固定频率在积压时会不断叠加并发执行。</p>
     *
     * <p>默认 1 分钟一轮。支付窗口是分钟级（默认 30 分钟），1 分钟的检测粒度
     * 完全够用；再密只是徒增空转的数据库查询。</p>
     */
    @Scheduled(
            fixedDelayString = "${mall.order.close-scan-interval-ms:60000}",
            initialDelayString = "${mall.order.close-scan-initial-delay-ms:60000}")
    public void closeTimedOutOrders() {
        List<OrderEntity> candidates = orderMapper.selectTimeoutCandidates(BATCH_LIMIT);
        if (candidates.isEmpty()) {
            return;
        }

        int closed = 0;
        int skipped = 0;
        for (OrderEntity candidate : candidates) {
            try {
                // 在循环里逐单调 → 每单一个独立事务（见类注释）。
                // 抢不到（已被支付/已取消/被并发关掉）返回 false，静默跳过即可，
                // 这不是错误，是正常的竞争结果。
                if (orderPersistenceService.cancel(
                        candidate.getId(), OrderPersistenceService.REASON_TIMEOUT)) {
                    closed++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                // 单笔失败不影响其余 —— 这正是"一单一事务"要换来的隔离性。
                // 不额外记录"待重试"状态：下一轮扫描自然还会捞到它（close_deadline 仍在过去）。
                log.error("超时关单失败，下一轮重试。orderId={}，orderNo={}",
                        candidate.getId(), candidate.getOrderNo(), e);
            }
        }
        log.info("超时关单完成：本轮候选 {}，成功关闭 {}，竞争跳过 {}", candidates.size(), closed, skipped);
    }
}
