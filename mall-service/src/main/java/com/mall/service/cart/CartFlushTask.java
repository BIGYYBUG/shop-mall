package com.mall.service.cart;

import com.mall.entity.CartItemEntity;
import com.mall.storage.CartItem;
import com.mall.storage.CartStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 购物车异步落库任务 —— 把 Redis 里的"待落库"用户批量同步回 MySQL。
 *
 * <p>这是「Redis 主存」方案里唯一把数据写回磁盘的地方。它把 N 次用户操作
 * 合并成 1 次落库：某用户一分钟里改了 20 次数量，最终只需要写一次。</p>
 *
 * <h3>刷库流程（每轮对每个待落库用户）</h3>
 * <pre>
 *   1. Redis 里没有这辆车？  → 直接摘掉标记，跳过
 *      （车没了说明是过期/丢失，此时 MySQL 才是权威，绝不能拿空车去覆盖它）
 *   2. v1 = version()
 *   3. 读快照 → 一个事务内「删旧行 + 插新行」
 *   4. v2 = version()
 *      v1 == v2 → 期间没人改过，安全摘掉标记
 *      v1 != v2 → 期间有新写入，保留标记，下一轮用最新快照覆盖
 * </pre>
 *
 * <p>第 4 步是关键：刷库是「读 Redis → 写 MySQL」，中间有时间窗口。
 * 若窗口内用户又改了购物车，这次写进去的就是<b>旧快照</b>。保留脏标记后，
 * 下一轮会用新快照覆盖它，最终一致。若不加版本判断直接摘标记，
 * 新数据就可能永久丢失 —— 直到用户下次操作才会被重新标脏。</p>
 *
 * <h3>失败处理</h3>
 * <p>任何异常都<b>不摘标记</b>，等下一轮重试。宁可反复重试，也不能丢数据。</p>
 *
 * <h3>已知取舍</h3>
 * <p>单实例部署下这套"脏集合 + 定时刷"足够；多实例时会重复刷同一个用户
 * （幂等，不会错，只是浪费）。要避免重复，需要分布式锁或改由 MQ 消费 ——
 * 阶段四引入 MQ 后可以顺带换掉。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartFlushTask {

    private final CartStore cartStore;
    private final CartPersistenceService cartPersistenceService;

    /**
     * 周期性把待落库的购物车写回 MySQL。
     *
     * <p>用 {@code fixedDelay}（上一轮结束后再等 N 毫秒）而不是 {@code fixedRate}
     * （固定频率）：刷库耗时不可控，固定频率在积压时会不断叠加并发执行。</p>
     */
    @Scheduled(
            fixedDelayString = "${mall.cart.flush-interval-ms:30000}",
            initialDelayString = "${mall.cart.flush-initial-delay-ms:30000}")
    public void flushPending() {
        Set<Long> users = cartStore.pendingUsers();
        if (users.isEmpty()) {
            return;
        }
        int flushed = 0;
        int skipped = 0;
        for (Long userId : users) {
            try {
                if (flushOne(userId)) {
                    flushed++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                // 不摘标记 → 下一轮重试
                log.error("购物车落库失败，保留脏标记待重试。userId={}", userId, e);
            }
        }
        log.info("购物车落库完成：成功 {}，延后 {}，本轮待处理 {}", flushed, skipped, users.size());
    }

    /**
     * 落库单个用户。
     *
     * @return true 表示已成功落库并摘掉标记；false 表示本轮跳过（车已不在，或期间有新写入）
     */
    private boolean flushOne(Long userId) {
        if (!cartStore.exists(userId)) {
            // 车已经不在 Redis 里（过期/丢失）。此时 MySQL 是唯一权威，
            // 绝不能"因为读到空车就把库清掉"—— 那才叫真丢数据。
            cartStore.markFlushed(userId);
            log.warn("Redis 中已无该用户的购物车，跳过落库以免覆盖库中数据。userId={}", userId);
            return false;
        }

        long versionBefore = cartStore.version(userId);
        List<CartItem> items = cartStore.getItems(userId);

        List<CartItemEntity> rows = items.stream()
                .map(item -> CartServiceImpl.toEntity(userId, item))
                .toList();
        cartPersistenceService.replaceAll(userId, rows);

        long versionAfter = cartStore.version(userId);
        if (versionBefore == versionAfter) {
            cartStore.markFlushed(userId);
            return true;
        }
        log.info("落库期间购物车又有新写入，保留脏标记待下轮覆盖。userId={}，版本 {} → {}",
                userId, versionBefore, versionAfter);
        return false;
    }
}
