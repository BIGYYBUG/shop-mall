package com.mall.service.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.api.dto.OrderBuyNowDTO;
import com.mall.api.dto.OrderCheckoutDTO;
import com.mall.api.vo.CartItemVO;
import com.mall.api.vo.CartVO;
import com.mall.api.vo.OrderVO;
import com.mall.api.vo.PageVO;
import com.mall.common.exception.BusinessException;
import com.mall.convert.order.OrderVoFactory;
import com.mall.entity.OrderEntity;
import com.mall.entity.OrderItemEntity;
import com.mall.mapper.OrderItemMapper;
import com.mall.mapper.OrderMapper;
import com.mall.service.cart.CartService;
import com.mall.storage.CartStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单服务实现 —— 负责<b>编排</b>，不负责事务与 SQL。
 *
 * <h3>职责切分</h3>
 * <ul>
 *   <li><b>本类</b>：鉴权兜底、归属校验、请求归一化、防重复提交、把事务委托出去、装配出参；</li>
 *   <li><b>{@link OrderPersistenceService}</b>：事务边界与"先 CAS 再回补"这类顺序判据；</li>
 *   <li><b>{@link OrderMapper}</b>：带条件的原子语句。</li>
 * </ul>
 *
 * <h3>为什么下单用一个 Redis 锁，而且不主动释放</h3>
 *
 * <p>用户双击"提交订单"会发两个几乎同时的请求，两次都合法、都会扣库存 ——
 * 这不是幂等能解决的（没有幂等键），只能靠"同一用户 5 秒内只允许一次"挡住。</p>
 *
 * <p>锁<b>不主动 del</b>，靠 TTL 自然过期。原因是：如果下单结束就删锁，
 * 锁的存活时间等于"一次下单的耗时"（可能只有十几毫秒），双击的第二击
 * （通常间隔 100~300ms）就照样能进来，锁等于白加。让它固定存活 5 秒，
 * 才能真正覆盖"手抖连点"这个窗口。</p>
 *
 * <p><b>Redis 异常时放行，不放行才是错的</b>：这个锁是防重复提交的优化，
 * 不是正确性依赖（库存的并发安全由数据库行锁保证）。如果因为 Redis 挂了
 * 就让所有人不能下单，是把一个可用性风险升级成了业务中断。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    /** 支付方式：模拟支付。接入真实支付后这里会变成枚举（微信/支付宝/…） */
    private static final int PAY_TYPE_MOCK = 1;

    /** 下单锁的 key 前缀，按用户维度，避免不同用户互相干扰 */
    private static final String ORDER_LOCK_PREFIX = "mall:order:lock:";

    /** 下单锁的存活时间。见类注释：不主动释放，靠 TTL 覆盖手抖连点窗口 */
    private static final Duration ORDER_LOCK_TTL = Duration.ofSeconds(5);

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderPersistenceService orderPersistenceService;
    private final OrderVoFactory orderVoFactory;
    private final CartService cartService;
    private final CartStore cartStore;
    private final StringRedisTemplate redisTemplate;

    /** 支付窗口（分钟）。抽成配置项而不是代码常量，便于联调时改成 1 分钟测超时关单 */
    @Value("${mall.order.pay-timeout-minutes:30}")
    private int payTimeoutMinutes;

    // ==================================================================
    // 买家侧：下单
    // ==================================================================

    @Override
    public OrderVO placeOrderFromCart(Long userId, OrderCheckoutDTO dto) {
        requireLogin(userId);

        // 只结算「已勾选 且 可购买」的条目 —— 结算哪些商品由服务端判定，
        // 不由请求传参决定（契约里根本没有 productIds 字段）
        List<OrderCreateCommand.Line> lines = selectedLinesOf(userId);
        if (lines.isEmpty()) {
            throw new BusinessException(400, "请先在购物车中勾选要结算的商品");
        }

        OrderCreateCommand cmd = new OrderCreateCommand(
                userId, lines,
                dto.receiverName(), dto.receiverPhone(), dto.receiverAddress(), dto.remark(),
                payTimeoutMinutes);
        return place(userId, cmd, lines.stream().map(OrderCreateCommand.Line::productId).toList(), true);
    }

    @Override
    public OrderVO placeOrderBuyNow(Long userId, OrderBuyNowDTO dto) {
        requireLogin(userId);

        OrderCreateCommand cmd = new OrderCreateCommand(
                userId,
                List.of(new OrderCreateCommand.Line(dto.productId(), dto.quantity())),
                dto.receiverName(), dto.receiverPhone(), dto.receiverAddress(), dto.remark(),
                payTimeoutMinutes);
        // 直接购买不涉及购物车，下单后无需清理任何购物车条目
        return place(userId, cmd, List.of(), false);
    }

    // ==================================================================
    // 买家侧：查询
    // ==================================================================

    @Override
    public PageVO<OrderVO> pageMyOrders(Long userId, Integer status, long pageNum, long pageSize) {
        requireLogin(userId);
        LambdaQueryWrapper<OrderEntity> wrapper = Wrappers.<OrderEntity>lambdaQuery()
                .eq(OrderEntity::getUserId, userId)
                .eq(status != null, OrderEntity::getStatus, status)
                // 按 id 倒序：自增 id 与创建时间单调同序，因此等价于"按时间倒序"，
                // 且能直接吃 idx_user_all / idx_user_status 的排序列，不会 filesort
                .orderByDesc(OrderEntity::getId);
        return toPageVO(orderMapper.selectPage(new Page<>(pageNum, pageSize), wrapper));
    }

    @Override
    public OrderVO getMyOrder(Long userId, String orderNo) {
        requireLogin(userId);
        OrderEntity order = requireMyOrder(userId, orderNo);
        return withItems(order);
    }

    // ==================================================================
    // 买家侧：状态流转
    // ==================================================================

    @Override
    public OrderVO pay(Long userId, String orderNo) {
        requireLogin(userId);
        OrderEntity order = requireMyOrder(userId, orderNo);

        if (!orderPersistenceService.pay(order.getId(), PAY_TYPE_MOCK)) {
            throw new BusinessException(409,
                    "订单当前状态为「" + OrderStatus.text(order.getStatus()) + "」，无法支付");
        }
        return withItems(reload(order.getId()));
    }

    @Override
    public OrderVO cancel(Long userId, String orderNo) {
        requireLogin(userId);
        OrderEntity order = requireMyOrder(userId, orderNo);

        if (!orderPersistenceService.cancel(order.getId(), OrderPersistenceService.REASON_BUYER)) {
            throw new BusinessException(409,
                    "订单当前状态为「" + OrderStatus.text(order.getStatus()) + "」，无法取消");
        }
        return withItems(reload(order.getId()));
    }

    @Override
    public OrderVO confirm(Long userId, String orderNo) {
        requireLogin(userId);
        OrderEntity order = requireMyOrder(userId, orderNo);

        if (!orderPersistenceService.confirm(order.getId())) {
            throw new BusinessException(409,
                    "订单当前状态为「" + OrderStatus.text(order.getStatus()) + "」，无法确认收货");
        }
        return withItems(reload(order.getId()));
    }

    // ==================================================================
    // 平台侧
    // ==================================================================

    @Override
    public PageVO<OrderVO> pageAdminOrders(long pageNum, long pageSize,
                                           Integer status, String orderNo, Long userId) {
        LambdaQueryWrapper<OrderEntity> wrapper = Wrappers.<OrderEntity>lambdaQuery()
                .eq(status != null, OrderEntity::getStatus, status)
                .eq(userId != null, OrderEntity::getUserId, userId)
                // 订单号用 like 而不是 eq：运营通常只记得后几位
                .like(StringUtils.hasText(orderNo), OrderEntity::getOrderNo, orderNo)
                .orderByDesc(OrderEntity::getId);
        return toPageVO(orderMapper.selectPage(new Page<>(pageNum, pageSize), wrapper));
    }

    @Override
    public OrderVO getOrderForAdmin(String orderNo) {
        return withItems(requireByOrderNo(orderNo));
    }

    @Override
    public OrderVO ship(String orderNo) {
        OrderEntity order = requireByOrderNo(orderNo);

        if (!orderPersistenceService.ship(order.getId())) {
            throw new BusinessException(409,
                    "订单当前状态为「" + OrderStatus.text(order.getStatus()) + "」，无法发货");
        }
        return withItems(reload(order.getId()));
    }

    @Override
    public OrderVO close(String orderNo) {
        OrderEntity order = requireByOrderNo(orderNo);

        if (!orderPersistenceService.cancel(order.getId(), OrderPersistenceService.REASON_ADMIN)) {
            throw new BusinessException(409,
                    "订单当前状态为「" + OrderStatus.text(order.getStatus()) + "」，无法关闭");
        }
        return withItems(reload(order.getId()));
    }

    // ==================================================================
    // 内部：下单主流程
    // ==================================================================

    /**
     * 下单的统一入口。
     *
     * @param purchasedProductIds 本次买走的商品 ID（用于下单后清理购物车）；直接购买时为空
     * @param clearCart           是否需要清理购物车
     */
    private OrderVO place(Long userId, OrderCreateCommand cmd,
                          List<Long> purchasedProductIds, boolean clearCart) {
        String lockKey = ORDER_LOCK_PREFIX + userId;
        if (!tryLock(lockKey)) {
            throw new BusinessException(429, "请勿重复提交，请稍后再试");
        }

        // 事务在这里开始、在这里结束。之后的所有动作都发生在事务之外 ——
        // 这一点很关键，见下面清理购物车的说明。
        OrderWithItems created = orderPersistenceService.createOrder(cmd);

        if (clearCart && !purchasedProductIds.isEmpty()) {
            clearPurchasedItems(userId, purchasedProductIds);
        }
        return orderVoFactory.create(created.order(), created.items());
    }

    /**
     * 下单成功后清理购物车里已被买走的条目。
     *
     * <p><b>为什么必须放在事务提交之后</b>：购物车在 Redis，而 Redis
     * <b>不受 MySQL 事务管辖</b>。如果把它放进事务内部，一旦后面还有别的步骤失败，
     * 数据库回滚了、Redis 却已经被删 —— 用户会发现"订单没了，购物车也空了"。
     * 放在事务之后，最坏的结果只是"订单成功、购物车残留"，
     * 用户手动删一下即可，属于可接受的降级。</p>
     *
     * <p><b>为什么失败了不影响下单</b>：订单已经落库并提交，购物车只是体验层数据。
     * 为了清理购物车失败而让用户看到下单失败，是本末倒置。</p>
     */
    private void clearPurchasedItems(Long userId, List<Long> productIds) {
        try {
            // 直接用 CartStore 而不是 CartService.removeItems：
            // 后者会顺带装配并返回整车 VO（含一次商品批量查询），
            // 而这里只需要"删掉那几个 field"，拿那个 VO 纯属浪费。
            cartStore.removeItems(userId, productIds);
        } catch (Exception e) {
            log.warn("下单成功但清理购物车失败，用户可手动删除。userId={}，productIds={}",
                    userId, productIds, e);
        }
    }

    /**
     * 取购物车里「已勾选 且 可购买」的条目，并按商品合并数量。
     *
     * <p>合并是防御性的：唯一键 {@code uk_user_product} 已经保证同一商品只有一行，
     * 但"下单行的数量必须唯一"这一点太重要了 —— 一旦将来有人改了购物车结构
     * （比如引入多规格），重复行的后果是库存被扣两次、件数统计翻倍。
     * 在归一化的地方顺手合并，成本几乎为零。</p>
     */
    private List<OrderCreateCommand.Line> selectedLinesOf(Long userId) {
        CartVO cart = cartService.getCart(userId);
        Map<Long, Integer> merged = new LinkedHashMap<>();
        for (CartItemVO item : cart.getItems()) {
            if (!Boolean.TRUE.equals(item.getSelected()) || !Boolean.TRUE.equals(item.getAvailable())) {
                continue;
            }
            merged.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }
        return merged.entrySet().stream()
                .map(e -> new OrderCreateCommand.Line(e.getKey(), e.getValue()))
                .toList();
    }

    // ==================================================================
    // 内部：查询与校验
    // ==================================================================

    /**
     * 取"我的订单"，不存在或不属于自己一律 404。
     *
     * <p><b>为什么不返回 403</b>：403 等于告诉调用方"这个订单是真实存在的，
     * 只是不是你的"——攻击者据此可以判断单号是否有效，进而估算全站订单量。
     * 对不属于自己的资源，"不存在"才是最恰当的回答。</p>
     */
    private OrderEntity requireMyOrder(Long userId, String orderNo) {
        OrderEntity order = requireByOrderNo(orderNo);
        if (!order.getUserId().equals(userId)) {
            log.warn("越权访问订单被拒：orderNo={}，操作者 userId={}，归属 userId={}",
                    orderNo, userId, order.getUserId());
            throw new BusinessException(404, "订单不存在");
        }
        return order;
    }

    private OrderEntity requireByOrderNo(String orderNo) {
        if (!StringUtils.hasText(orderNo)) {
            throw new BusinessException(400, "订单号不能为空");
        }
        OrderEntity order = orderMapper.selectOne(
                Wrappers.<OrderEntity>lambdaQuery().eq(OrderEntity::getOrderNo, orderNo));
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        return order;
    }

    private OrderEntity reload(Long orderId) {
        OrderEntity order = orderMapper.selectById(orderId);
        if (order == null) {
            // 理论上到不了这里：刚刚才成功改过状态，不可能同时被删
            throw new BusinessException(404, "订单不存在");
        }
        return order;
    }

    /** 装配含明细的订单。 */
    private OrderVO withItems(OrderEntity order) {
        List<OrderItemEntity> items = orderItemMapper.selectByOrderIds(List.of(order.getId()));
        return orderVoFactory.create(order, items);
    }

    private PageVO<OrderVO> toPageVO(Page<OrderEntity> page) {
        return new PageVO<>(
                page.getTotal(),
                page.getPages(),
                page.getCurrent(),
                page.getSize(),
                // createList 走 VoFactory 的默认实现，逐条装配且不带明细 ——
                // 列表只需要单号/状态/金额/件数，件数取主表冗余列，不必 join 明细
                orderVoFactory.createList(page.getRecords()));
    }

    /**
     * 兜底登录校验。
     *
     * <p>{@code /order/**} 不在放行清单里，无令牌的请求已经被 {@code JwtInterceptor}
     * 挡在 401。这里再判一次是为了防两种情况：有人把 {@code /order/**} 加进放行清单，
     * 或者有人绕开 Web 层直接调 Service。</p>
     */
    private void requireLogin(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
    }

    /**
     * 尝试获取下单锁。
     *
     * <p>用 {@code SET key value NX EX}（Redis 2.6.12+ 支持，本项目 3.0.504 可用）。
     * Redis 抛异常时<b>返回 true 放行</b> —— 锁是防重复提交的优化，
     * 不是正确性依赖（并发安全由数据库行锁保证），不该因为它挂了就中断下单。</p>
     */
    private boolean tryLock(String key) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(key, "1", ORDER_LOCK_TTL));
        } catch (Exception e) {
            log.warn("下单锁不可用（Redis 异常），本次放行：{}", e.getMessage());
            return true;
        }
    }
}
