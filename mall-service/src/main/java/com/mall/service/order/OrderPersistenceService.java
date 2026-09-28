package com.mall.service.order;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.entity.OrderEntity;
import com.mall.entity.OrderItemEntity;
import com.mall.entity.ProductEntity;
import com.mall.mapper.OrderItemMapper;
import com.mall.mapper.OrderMapper;
import com.mall.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 订单的事务边界 —— 所有需要「要么全成、要么全不成」的写操作都在这里。
 *
 * <h3>为什么必须单独一个 Bean</h3>
 *
 * <p>{@code @Transactional} 是基于<b>代理</b>生效的：在同一个类里自己调自己
 * （self-invocation）不走代理，注解会静默失效 —— 没有报错，只是事务没开。
 * 项目里 {@code CartPersistenceService} 已经是同一个理由拆出来的，
 * 这里沿用同一手法。</p>
 *
 * <h3>为什么不把循环放进一个事务里（关单任务特别重要）</h3>
 *
 * <p>调用方（{@code OrderTimeoutTask}）是<b>一单一事务</b>地调用本类的方法，
 * 而不是"把 200 个超时订单一次性传进来"。原因有两个：</p>
 * <ol>
 *   <li><b>长事务持锁</b>：一批订单里只要有一单慢，其余订单持有的行锁
 *       就一直不释放，会拖垮并发的下单请求；</li>
 *   <li><b>失败隔离</b>：一单失败不该让另外 199 单一起回滚。
 *       分开事务后失败的那单下轮会重试，其余照常完成。</li>
 * </ol>
 *
 * <h3>与 Mapper 的分工</h3>
 *
 * <p>本类负责<b>顺序与判据</b>（先 CAS 改状态、再回补库存；affected rows 是不是 1），
 * Mapper 负责<b>语句本身</b>（带条件的原子更新）。二者的分工是刻意的：
 * "回补库存不能发生在 CAS 成功之前"这条规则属于业务，不属于 SQL。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPersistenceService {

    /** 取消原因：买家主动取消。与超时、平台关闭共用同一个 CAS 语句，只有这个值不同 */
    public static final String REASON_BUYER = "买家取消";
    /** 取消原因：支付窗口超时，由定时任务关闭 */
    public static final String REASON_TIMEOUT = "超时未支付";
    /** 取消原因：平台运营强制关闭（仅限待支付订单） */
    public static final String REASON_ADMIN = "平台关闭";

    private static final DateTimeFormatter ORDER_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;

    // ==================================================================
    // 下单
    // ==================================================================

    /**
     * 创建订单：查商品 → 扣库存 → 写主表 → 写明细，全部在一个事务里。
     *
     * <p><b>顺序为什么是「先扣库存、后插订单」</b>：扣库存会锁住会被并发争用的
     * {@code mall_product} 行，而订单主表几乎不会有人去改。把锁争用的动作
     * 放到最后、让它的持锁窗口只覆盖后面两条 insert，是把临界区压到最短。</p>
     *
     * <p><b>扣库存为什么按 productId 升序</b>：一笔订单包含多个商品时，
     * 两个并发订单若以相反顺序去锁同样的两行商品，就会互相等待形成死锁
     * （A 拿 1 等 2，B 拿 2 等 1）。统一按主键升序加锁是消除这类死锁的标准手法。</p>
     *
     * <p>中间任何一步抛异常，整个事务回滚 —— <b>已经扣掉的库存会自动还回去</b>，
     * 不需要写补偿代码。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderWithItems createOrder(OrderCreateCommand cmd) {
        // ---------- 1. 读商品事实（一次 IN 查完，避免 N+1） ----------
        List<Long> productIds = cmd.lines().stream()
                .map(OrderCreateCommand.Line::productId)
                .toList();
        Map<Long, ProductEntity> products = loadProducts(productIds);

        // ---------- 2. 校验商品并构造明细快照 ----------
        // 这里的校验是为了给出"哪个商品有问题"这种可读的错误信息；
        // 真正的并发安全由第 3 步的条件 UPDATE 负责，不能只靠这里的检查。
        List<OrderItemEntity> items = new ArrayList<>(cmd.lines().size());
        BigDecimal totalAmount = BigDecimal.ZERO;
        int itemCount = 0;

        for (OrderCreateCommand.Line line : cmd.lines()) {
            ProductEntity product = products.get(line.productId());
            if (product == null) {
                throw new BusinessException(400, "商品不存在或已被删除");
            }
            if (product.getStatus() == null || product.getStatus() != 1) {
                throw new BusinessException(400, "商品「" + product.getName() + "」已下架");
            }
            int quantity = line.quantity();
            if (product.getStock() == null || product.getStock() < quantity) {
                throw new BusinessException(400, "商品「" + product.getName() + "」库存不足");
            }

            BigDecimal subtotal = multiply(product.getPrice(), quantity);
            totalAmount = totalAmount.add(subtotal);
            itemCount += quantity;

            OrderItemEntity item = new OrderItemEntity();
            item.setProductId(product.getId());
            // 0 = 平台自营。用 0 而不是 null，语义清晰且索引可正常使用
            item.setSellerId(product.getSellerId() == null ? 0L : product.getSellerId());
            item.setProductName(product.getName());
            item.setProductCoverKey(product.getCoverKey());
            item.setPrice(product.getPrice());
            item.setQuantity(quantity);
            item.setSubtotal(subtotal);
            items.add(item);
        }

        // ---------- 3. 扣库存（带条件的原子更新，按主键升序防死锁） ----------
        List<OrderCreateCommand.Line> sortedLines = cmd.lines().stream()
                .sorted(Comparator.comparing(OrderCreateCommand.Line::productId))
                .toList();
        for (OrderCreateCommand.Line line : sortedLines) {
            int affected = productMapper.deductStock(line.productId(), line.quantity());
            if (affected != 1) {
                // affected = 0 说明"库存不足 / 已下架 / 已被删除"三者之一。
                // 抛出去让事务回滚 —— 本循环此前扣掉的那些会自动还回去。
                throw new BusinessException(400, "商品库存不足，请调整数量后重试");
            }
        }

        // ---------- 4. 写订单主表 ----------
        OrderEntity order = new OrderEntity();
        order.setOrderNo(generateOrderNo());
        order.setUserId(cmd.userId());
        order.setTotalAmount(totalAmount);
        // 运费与优惠本轮恒为 0，但字段保留，口径写成恒等式
        order.setFreightAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        order.setDiscountAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        order.setPayAmount(totalAmount
                .add(order.getFreightAmount())
                .subtract(order.getDiscountAmount())
                .setScale(2, RoundingMode.HALF_UP));
        order.setItemCount(itemCount);
        order.setStatus(OrderStatus.PENDING.code());
        order.setReceiverName(cmd.receiverName());
        order.setReceiverPhone(cmd.receiverPhone());
        order.setReceiverAddress(cmd.receiverAddress());
        order.setRemark(cmd.remark());
        order.setCloseDeadline(LocalDateTime.now().plusMinutes(cmd.payTimeoutMinutes()));
        orderMapper.insert(order);

        // ---------- 5. 写明细（回填订单 ID） ----------
        for (OrderItemEntity item : items) {
            item.setOrderId(order.getId());
        }
        orderItemMapper.batchInsert(items);

        log.info("下单成功：orderNo={}，userId={}，金额={}，件数={}",
                order.getOrderNo(), cmd.userId(), order.getPayAmount(), itemCount);
        return new OrderWithItems(order, items);
    }

    // ==================================================================
    // 状态流转
    // ==================================================================

    /**
     * 支付成功：0 → 1，并累加商品销量。
     *
     * <p><b>销量为什么在这里加、而不是下单时加</b>：销量语义上单调递增。
     * 下单就加、取消再减，会让 sales 退化成"当前有效订单件数"且数值会回退。
     * 支付之后本轮再无回退路径（无退款），语义干净。</p>
     *
     * @return true = 本次支付成功；false = 订单已不是待支付（重复支付 / 已被取消或超时关闭）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean pay(Long orderId, Integer payType) {
        if (orderMapper.casPay(orderId, payType) != 1) {
            return false;
        }
        for (OrderItemEntity item : itemsOf(orderId)) {
            productMapper.increaseSales(item.getProductId(), item.getQuantity());
        }
        return true;
    }

    /**
     * 发货：1 → 2。单条语句，无需显式事务。
     *
     * @return true = 发货成功；false = 订单已不是已支付
     */
    public boolean ship(Long orderId) {
        // 单条 UPDATE 本身即原子操作，@Transactional 在这里不增加任何保证，
        // 因此刻意不加注解 —— 事务注解不是"加了更安全"，它是有成本的（连接占用）
        return orderMapper.casShip(orderId) == 1;
    }

    /**
     * 确认收货：2 → 3。单条语句，无需显式事务。
     *
     * @return true = 成功；false = 订单已不是已发货
     */
    public boolean confirm(Long orderId) {
        return orderMapper.casConfirm(orderId) == 1;
    }

    /**
     * 取消 / 关闭订单：0 → 4，并回补库存。
     *
     * <p><b>⚠️ 顺序是本方法最关键的地方：必须先 CAS 改状态、拿到 affected = 1，
     * 才允许回补库存。</b>反过来写（先回补再改状态）在并发下会重复回补 ——
     * 两次取消请求都执行了 +库存，只有一个 CAS 成功，库存凭空多出来。
     * 这里用「状态机当作幂等闸门」：CAS 成功本身即代表"我抢到了这次取消的权利"。</p>
     *
     * @param reason 取消原因，取值见本类的 {@code REASON_*} 常量
     * @return true = 本次成功关闭；false = 订单已不是待支付（已被支付/已取消/被并发关掉）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean cancel(Long orderId, String reason) {
        if (orderMapper.casCancel(orderId, reason) != 1) {
            return false;
        }
        List<OrderItemEntity> items = itemsOf(orderId);
        for (OrderItemEntity item : items) {
            productMapper.restoreStock(item.getProductId(), item.getQuantity());
        }
        log.info("订单已关闭：orderId={}，原因={}，回补商品行数={}", orderId, reason, items.size());
        return true;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 一次 IN 查完所需的商品。
     *
     * <p>MyBatis-Plus 的 {@code @TableLogic} 会自动追加 {@code deleted = 0}，
     * 因此已被逻辑删除的商品查不到 —— 下单时会被判为"商品不存在"，符合预期。</p>
     */
    private Map<Long, ProductEntity> loadProducts(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        List<ProductEntity> found = productMapper.selectList(
                Wrappers.<ProductEntity>lambdaQuery().in(ProductEntity::getId, productIds));
        Map<Long, ProductEntity> map = new HashMap<>(found.size() * 2);
        for (ProductEntity product : found) {
            map.put(product.getId(), product);
        }
        return map;
    }

    /** 取某订单的明细（状态流转需要按明细回补库存 / 累加销量）。 */
    private List<OrderItemEntity> itemsOf(Long orderId) {
        return orderItemMapper.selectByOrderIds(List.of(orderId));
    }

    private BigDecimal multiply(BigDecimal price, int quantity) {
        if (price == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 生成订单号：{@code yyyyMMdd}（8 位）+ 随机十六进制（24 位）= 32 位。
     *
     * <p><b>为什么带日期前缀</b>：排查问题时，客服只报一个单号，
     * 光看前 8 位就知道是哪天下的单，不需要回查数据库。</p>
     *
     * <p><b>为什么后面是随机而不是自增序列</b>：自增序列在同一时刻只能有一个写入者，
     * 要么依赖数据库（多一次往返），要么依赖应用内锁（多实例下失效）。
     * 96 bit 的随机值在同一天内的碰撞概率可以忽略，再由
     * {@code uk_order_no} 唯一键兜住极小概率的碰撞 —— 这是"先选简单方案，
     * 让数据库约束做最后一道防线"的典型取舍。</p>
     */
    private String generateOrderNo() {
        String date = LocalDate.now().format(ORDER_NO_DATE);
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        return date + random;
    }
}
