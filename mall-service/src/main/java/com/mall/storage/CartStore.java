package com.mall.storage;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 购物车主存储 —— Redis 是<b>真源</b>，MySQL 是<b>异步备份</b>。
 *
 * <p>放在 {@code com.mall.storage} 而非 {@code service.cart}，遵循项目既有约定：
 * 「存储能力 = 接口 + 实现同包，实现按渠道分子包」（{@code local/ oss/ memory/ redis/}）。
 * 已有 {@code FileStorageService}、{@code ChatMemoryStore}，购物车是同一类"存东西"的能力。</p>
 *
 * <h2>这个方案（Redis 主 + 异步落库）意味着什么</h2>
 *
 * <pre>
 *   写：只打 Redis（并记一条"待落库"标记）→ 立即返回
 *   读：只读 Redis；若 Redis 里没有这辆车 → 回源 MySQL 重建
 *   刷：后台定时把标记过的车批量写回 MySQL
 * </pre>
 *
 * <p><b>换来的是</b>：写路径不碰磁盘、不碰数据库连接，高频改数量/勾选几乎零成本。</p>
 * <p><b>付出的是</b>：① 存在"丢窗口"——落库间隔内的数据只在 Redis，
 * 进程或 Redis 异常会丢这个窗口；② Redis 成了唯一真源，<b>必须开持久化</b>
 * （AOF everysec 或合理频率的 RDB），否则一次崩溃就是全量丢车；
 * ③ 一致性要自己维护（见下面的版本号）。</p>
 *
 * <h2>三个必须知道的设计点</h2>
 *
 * <h3>1. 为什么"待落库"要单独记一个集合，而不是每写一次就落一次库</h3>
 * <p>每写一次就落库 = 又回到了同步写库，白折腾。记集合是为了把 N 次写合并成 1 次落库
 * （同一个用户一分钟里改 20 次数量，只需要在最后写一次）。</p>
 *
 * <h3>2. 为什么需要版本号</h3>
 * <p>刷库是「读 Redis → 写 MySQL」，这中间存在时间窗口。若窗口内用户又改了购物车，
 * 那么本次落库写进去的是<b>旧快照</b>，会把新数据覆盖掉。做法是每次修改都让版本号 +1，
 * 刷库前后各读一次版本：</p>
 * <pre>
 *   v1 = version(uid)
 *   读 Redis 快照 → 写 MySQL
 *   v2 = version(uid)
 *   v1 == v2  → 期间没人改过，可以安全地摘掉"待落库"标记
 *   v1 != v2  → 期间有新写入，标记保留，下一轮再刷
 * </pre>
 *
 * <h3>3. 为什么"清空购物车"不能直接删 Redis key</h3>
 * <p>因为「key 不存在」和「车是空的」在刷库时必须区分开：前者表示 Redis 丢了数据
 * （此时 MySQL 才是权威，<b>绝不能</b>拿空车去覆盖它），后者是用户真的清空了
 * （应该如实把 MySQL 也清掉）。所以清空操作是"删掉所有商品字段、但把 key 留下来"，
 * 与"过期/丢失"在存储层天然可区分。</p>
 *
 * <h2>安全边界</h2>
 * <p>本接口<b>不做任何鉴权</b>。{@code userId} 必须由调用方从登录上下文
 * （{@code UserContext}）取得，<b>绝不能来自请求参数</b> —— 否则就成了
 * "传谁的 id 就能改谁的购物车"，典型的 IDOR。</p>
 */
public interface CartStore {

    // ==================================================================
    // 一、读写购物车
    // ==================================================================

    /**
     * 读取整车，按加购时间升序。
     *
     * @return 不可变列表；车不存在或为空时返回<b>空列表而非 null</b>
     */
    List<CartItem> getItems(Long userId);

    /**
     * 加购。已存在该商品则<b>在原有数量上累加</b>，并把勾选状态置回"选中"。
     */
    void addItem(Long userId, Long productId, int quantity);

    /**
     * 把某个商品的数量<b>设为</b>指定值（不是累加）。
     */
    void updateQuantity(Long userId, Long productId, int quantity);

    /**
     * 批量设置勾选状态。
     *
     * <p>必须是批量：结算前"全选/全不选"是最高频操作，逐条请求会产生 N 次往返。</p>
     *
     * @param productIds 目标商品；为空时表示不操作
     */
    void setSelected(Long userId, Collection<Long> productIds, boolean selected);

    /** 设置整车的勾选状态（全选 / 取消全选）。 */
    void setAllSelected(Long userId, boolean selected);

    /** 移除某个商品。 */
    void removeItem(Long userId, Long productId);

    /**
     * 清空整车。
     *
     * <p><b>不是删 key</b> —— 见类注释第 3 点：删 key 会与"Redis 丢数据"混淆，
     * 导致刷库时拿空车覆盖 MySQL。实现应保留 key 本身、只清掉商品字段。</p>
     */
    void clear(Long userId);

    /** 车内商品行数（用于顶部角标）。 */
    int count(Long userId);

    /** 该用户在 Redis 里是否有车（用于区分"空车"与"数据丢失"）。 */
    boolean exists(Long userId);

    /**
     * 用给定内容重建整车（回源 MySQL 后用），<b>不</b>标记为待落库。
     *
     * <p>重建是"Redis 丢了、从备份恢复"，不是用户的新写入，因此不应该反过来再触发一次落库。</p>
     */
    void replaceAll(Long userId, List<CartItem> items);

    // ==================================================================
    // 二、与异步落库的协作
    // ==================================================================

    /** 把该用户标记为"待落库"。实现需同时递增版本号。 */
    void markDirty(Long userId);

    /** 取出当前所有"待落库"的用户。 */
    Set<Long> pendingUsers();

    /** 落库成功且期间无新写入后，摘掉"待落库"标记。 */
    void markFlushed(Long userId);

    /** 读取当前版本号（刷库前后各读一次，用于判断是否发生了并发写入）。 */
    long version(Long userId);
}
