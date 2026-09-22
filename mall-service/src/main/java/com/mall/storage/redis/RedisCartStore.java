package com.mall.storage.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.storage.CartItem;
import com.mall.storage.CartStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 购物车的 Redis 实现 —— Redis 既是主存储，也是"待落库"队列。
 *
 * <h2>Key 设计</h2>
 * <pre>
 *   mall:cart:{userId}     Hash     field = productId  → {"q":数量,"s":勾选,"t":加购时间戳}
 *                                   field = __ver__    → 版本号（每次修改 +1）
 *   mall:cart:dirty        Set      待落库的 userId
 * </pre>
 *
 * <p><b>版本号为什么放在车这个 Hash 内部，而不是单开一个 key</b>：版本和车是同一份数据的
 * 两个侧面，生命周期必须一致。若单开 key，就会出现"车还在、版本已过期"的中间态，
 * 刷库时的 {@code v1 == v2} 判断会失真。放在一起后它们同生共死，天然一致。</p>
 *
 * <h2>⚠️ 本项目 Redis 版本是 3.0.504（2015 年），以下限制必须绕开</h2>
 * <ul>
 *   <li><b>不能用 {@code HSET} 一次写多个 field</b> —— 那是 4.0+ 的能力。
 *       本实现每个商品只写一个 field（值为 JSON），因此不受影响。</li>
 *   <li><b>没有 {@code UNLINK}</b>（4.0+）—— 删除一律用 {@code DEL}。车很小，不会阻塞。</li>
 *   <li><b>{@code SPOP key count} 不可用</b>（3.2+）—— 取出待落库用户用 {@code SMEMBERS}。</li>
 *   <li><b>没有 RedisJSON 模块</b> —— 值就是普通字符串，JSON 由 Jackson 自己转。</li>
 *   <li><b>不要引 Redisson</b> —— 它对新特性依赖较多，对 3.0 兼容性差（阶段五会踩）。</li>
 * </ul>
 *
 * <h2>原子性说明（重要，勿混淆）</h2>
 * <p>{@link #addItem} 走的是 <b>EVAL 脚本</b>，因为它是"读出来 + 累加 + 写回去"，
 * 不原子就会丢更新（和数据库侧"先查再写"是同一个病）。Redis 从 2.6 起支持
 * {@code EVAL}，3.0.504 完全可用，所以这里没有理由降级成两步。</p>
 *
 * <p>而 {@link #updateQuantity} / {@link #setSelected} 是<b>设绝对值</b>
 * （"把数量设为 3"），语义上后写覆盖先写即可，不存在"读到旧值再累加"的丢失问题，
 * 因此保持简单的读改写。唯一残留窗口是：这两个操作与 {@code addItem} 极短时间内
 * 并发打同一商品时，可能互相覆盖 —— 购物车写入来自同一用户自己的会话，实践中
 * 并发极低。若将来要严格化，把这两处也换成 EVAL 即可，接口无需改动。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCartStore implements CartStore {

    /** 车 key 前缀 */
    private static final String CART_PREFIX = "mall:cart:";

    /** 待落库集合 */
    private static final String DIRTY_KEY = "mall:cart:dirty";

    /** 版本号在 Hash 内的字段名 */
    private static final String VER_FIELD = "__ver__";

    /**
     * 车的过期时间。到期后 Redis 没有这辆车，读取时会自动回源 MySQL 重建 ——
     * 这正是"Redis 丢了也不会真丢数据"的兜底路径。
     */
    private static final Duration CART_TTL = Duration.ofDays(30);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 加购的 EVAL 脚本：存在则累加、不存在则新建，并把勾选置回 1，最后版本 +1。
     *
     * <p>返回值是新数量，方便调用方直接回显。</p>
     */
    private static final RedisScript<Long> ADD_SCRIPT = new DefaultRedisScript<>("""
            local key   = KEYS[1]
            local field = ARGV[1]
            local inc   = tonumber(ARGV[2])
            local now   = tonumber(ARGV[3])

            local cur = redis.call('HGET', key, field)
            local qty = inc
            local addTime = now
            if cur then
              -- pcall 兜住"value 不是合法 JSON"的情况，避免整段脚本直接抛错
              local ok, old = pcall(cjson.decode, cur)
              if ok and old then
                qty = tonumber(old.q) + inc
                addTime = tonumber(old.t) or now
              end
            end

            redis.call('HSET', key, field, cjson.encode({q = qty, s = 1, t = addTime}))
            redis.call('HINCRBY', key, '__ver__', 1)
            return qty
            """, Long.class);

    private final StringRedisTemplate redis;

    // ==================================================================
    // 一、读写购物车
    // ==================================================================

    @Override
    public List<CartItem> getItems(Long userId) {
        Map<Object, Object> raw = redis.opsForHash().entries(cartKey(userId));
        if (raw.isEmpty()) {
            return List.of();
        }
        List<CartItem> items = new ArrayList<>(raw.size());
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            String field = String.valueOf(entry.getKey());
            if (VER_FIELD.equals(field)) {
                continue;
            }
            CartItem item = parse(field, String.valueOf(entry.getValue()));
            if (item != null) {
                items.add(item);
            }
        }
        items.sort(Comparator.comparingLong(CartItem::addTime));
        return List.copyOf(items);
    }

    @Override
    public void addItem(Long userId, Long productId, int quantity) {
        String key = cartKey(userId);
        redis.execute(ADD_SCRIPT, List.of(key),
                String.valueOf(productId),
                String.valueOf(quantity),
                String.valueOf(System.currentTimeMillis()));
        redis.expire(key, CART_TTL);
        markDirty(userId);
    }

    @Override
    public void updateQuantity(Long userId, Long productId, int quantity) {
        String key = cartKey(userId);
        CartItem old = readOne(key, productId);
        if (old == null) {
            // 商品不在车里 —— 静默忽略，不替调用方"顺手加一件"
            return;
        }
        redis.opsForHash().put(key, String.valueOf(productId), toJson(quantity, old.selected(), old.addTime()));
        bumpVersionAndMarkDirty(key, userId);
    }

    @Override
    public void setSelected(Long userId, Collection<Long> productIds, boolean selected) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        String key = cartKey(userId);
        boolean touched = false;
        for (Long productId : new LinkedHashSet<>(productIds)) {
            CartItem old = readOne(key, productId);
            if (old == null) {
                continue;
            }
            redis.opsForHash().put(key, String.valueOf(productId), toJson(old.quantity(), selected, old.addTime()));
            touched = true;
        }
        if (touched) {
            bumpVersionAndMarkDirty(key, userId);
        }
    }

    @Override
    public void setAllSelected(Long userId, boolean selected) {
        String key = cartKey(userId);
        List<CartItem> items = getItems(userId);
        if (items.isEmpty()) {
            return;
        }
        for (CartItem item : items) {
            redis.opsForHash().put(key, String.valueOf(item.productId()),
                    toJson(item.quantity(), selected, item.addTime()));
        }
        bumpVersionAndMarkDirty(key, userId);
    }

    @Override
    public void removeItem(Long userId, Long productId) {
        String key = cartKey(userId);
        Long removed = redis.opsForHash().delete(key, String.valueOf(productId));
        if (removed == null || removed == 0L) {
            return;
        }
        bumpVersionAndMarkDirty(key, userId);
    }

    @Override
    public void removeItems(Long userId, Collection<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        String key = cartKey(userId);
        // 去重后一次 HDEL：Redis 的 HDEL 支持多 field（2.0+，3.0.504 可用），
        // 比循环调用少 N-1 次往返。field 名是商品 id，不可能撞到 __ver__。
        Object[] fields = new LinkedHashSet<>(productIds).stream()
                .map(String::valueOf)
                .toArray();
        Long removed = redis.opsForHash().delete(key, fields);
        if (removed == null || removed == 0L) {
            // 一个都没删到 —— 不做版本变更，避免刷库任务为一次空操作白跑
            return;
        }
        bumpVersionAndMarkDirty(key, userId);
    }

    @Override
    public void clear(Long userId) {
        String key = cartKey(userId);
        // 刻意不用 DEL：删掉 key 就与"Redis 丢数据"无法区分了，
        // 刷库时会拿空车去覆盖 MySQL 里的备份。这里只清商品字段，保留 key 与版本号。
        Set<Object> fields = redis.opsForHash().keys(key);
        if (fields != null && !fields.isEmpty()) {
            Object[] productFields = fields.stream()
                    .filter(f -> !VER_FIELD.equals(String.valueOf(f)))
                    .toArray();
            if (productFields.length > 0) {
                redis.opsForHash().delete(key, productFields);
            }
        }
        // 即便车上本来就没东西，也把 key 立起来（exists 必须为 true），否则刷库会跳过这次清空
        redis.opsForHash().putIfAbsent(key, VER_FIELD, "0");
        bumpVersionAndMarkDirty(key, userId);
    }

    @Override
    public int count(Long userId) {
        String key = cartKey(userId);
        Long size = redis.opsForHash().size(key);
        if (size == null || size == 0L) {
            return 0;
        }
        Boolean hasVer = redis.opsForHash().hasKey(key, VER_FIELD);
        long count = Boolean.TRUE.equals(hasVer) ? size - 1 : size;
        return (int) Math.max(count, 0L);
    }

    @Override
    public boolean exists(Long userId) {
        return Boolean.TRUE.equals(redis.hasKey(cartKey(userId)));
    }

    @Override
    public void replaceAll(Long userId, List<CartItem> items) {
        String key = cartKey(userId);
        redis.delete(key);
        if (items == null || items.isEmpty()) {
            // 调用方约定：只有 MySQL 里确实有行时才调用本方法
            log.warn("replaceAll 收到空列表，userId={}，已清空 Redis 车", userId);
            return;
        }
        Map<String, String> hash = new java.util.HashMap<>(items.size() * 2);
        for (CartItem item : items) {
            hash.put(String.valueOf(item.productId()), toJson(item.quantity(), item.selected(), item.addTime()));
        }
        redis.opsForHash().putAll(key, hash);
        // 重建是"从备份恢复"，不是用户新写入：版本归零，且【不】标记待落库，
        // 否则会把刚从 MySQL 读出来的内容又原样写回去一遍。
        redis.opsForHash().put(key, VER_FIELD, "0");
        redis.expire(key, CART_TTL);
    }

    // ==================================================================
    // 二、与异步落库的协作
    // ==================================================================

    @Override
    public void markDirty(Long userId) {
        redis.opsForSet().add(DIRTY_KEY, String.valueOf(userId));
    }

    @Override
    public Set<Long> pendingUsers() {
        Set<String> members = redis.opsForSet().members(DIRTY_KEY);
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        Set<Long> users = new HashSet<>(members.size());
        for (String member : members) {
            try {
                users.add(Long.valueOf(member));
            } catch (NumberFormatException e) {
                // 脏数据不阻断刷库：顺手清掉，避免它永远卡在集合里
                log.warn("mall:cart:dirty 中存在非数字成员，已移除：{}", member);
                redis.opsForSet().remove(DIRTY_KEY, member);
            }
        }
        return users;
    }

    @Override
    public void markFlushed(Long userId) {
        redis.opsForSet().remove(DIRTY_KEY, String.valueOf(userId));
    }

    @Override
    public long version(Long userId) {
        Object ver = redis.opsForHash().get(cartKey(userId), VER_FIELD);
        if (ver == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(ver));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private String cartKey(Long userId) {
        return CART_PREFIX + userId;
    }

    /** 版本 +1 并标记待落库（所有会改动车的操作尾部都走这里，避免漏标）。 */
    private void bumpVersionAndMarkDirty(String key, Long userId) {
        redis.opsForHash().increment(key, VER_FIELD, 1);
        redis.expire(key, CART_TTL);
        markDirty(userId);
    }

    private CartItem readOne(String key, Long productId) {
        Object raw = redis.opsForHash().get(key, String.valueOf(productId));
        return raw == null ? null : parse(String.valueOf(productId), String.valueOf(raw));
    }

    private CartItem parse(String field, String json) {
        try {
            ItemValue value = MAPPER.readValue(json, ItemValue.class);
            return new CartItem(Long.valueOf(field), value.q(), value.s() == 1, value.t());
        } catch (Exception e) {
            // 单个字段坏掉不能拖垮整辆车：跳过并留痕
            log.warn("购物车条目反序列化失败，已跳过。field={}, value={}", field, json, e);
            return null;
        }
    }

    private String toJson(int quantity, boolean selected, long addTime) {
        try {
            return MAPPER.writeValueAsString(new ItemValue(quantity, selected ? 1 : 0, addTime));
        } catch (Exception e) {
            throw new IllegalStateException("购物车条目序列化失败", e);
        }
    }

    /** Redis 里存的紧凑结构，字段名取短以省内存（购物车是高基数小对象，量级可观）。 */
    private record ItemValue(int q, int s, long t) {
    }
}
