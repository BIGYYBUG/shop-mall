package com.mall;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.entity.OrderEntity;
import com.mall.entity.ProductEntity;
import com.mall.entity.RoleEntity;
import com.mall.entity.UserEntity;
import com.mall.entity.UserRoleEntity;
import com.mall.mapper.OrderMapper;
import com.mall.mapper.ProductMapper;
import com.mall.mapper.RoleMapper;
import com.mall.mapper.UserMapper;
import com.mall.mapper.UserRoleMapper;
import com.mall.service.order.OrderStatus;
import com.mall.service.order.OrderTimeoutTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单集成测试。
 *
 * <h3>这一组用例真正想守住的东西</h3>
 *
 * <p>订单的复杂性不在"接口能不能调通"，而在下面这几条不变量上。它们是
 * 高并发 + 状态机 + 库存三者交叉的地方，也是最容易出线上事故的地方：</p>
 *
 * <ol>
 *   <li><b>绝不超卖</b> —— 并发扣库存必须靠"条件写在 WHERE 里"的原子更新，
 *       而不是"先查再改"（{@link #concurrentDeductNeverOversells}）</li>
 *   <li><b>状态流转是 CAS，不是先查再改</b> —— 重复支付、取消已支付的单、
 *       重复取消，都必须被拒（{@link #paySucceedsThenDoublePayRejected} 等）</li>
 *   <li><b>回补库存只能在 CAS 成功之后</b> —— 顺序反了会在并发取消时重复回补，
 *       库存凭空增加（{@link #cancelRestoresStockAndSecondCancelRejected}）</li>
 *   <li><b>明细必须是快照</b> —— 商品改名改价不能改变历史订单
 *       （{@link #checkoutFromCartCreatesOrderWithSnapshotAndClearsCart}）</li>
 *   <li><b>金额永远服务端算</b> —— 契约里根本不存在金额字段，
 *       客户端无法影响定价（同上）</li>
 *   <li><b>IDOR 返回 404 而非 403</b> —— 403 会泄露"该订单真实存在"
 *       （{@link #buyerCannotSeeOthersOrder}）</li>
 *   <li><b>买家侧无权限码、平台侧有</b> —— 归属靠令牌，授权靠 RBAC
 *       （{@link #adminOrderEndpointsEnforcePermission}）</li>
 * </ol>
 *
 * <h3>前置条件</h3>
 * 本机 MySQL（mall 库，需已执行 03 / 06 / 07 / <b>10_mall_order.sql</b>）与
 * Redis 需处于运行状态。10 脚本里有 4 条订单权限码，缺了
 * {@link #adminOrderEndpointsEnforcePermission} 拿不到 order:list。
 *
 * <h3>运行方式</h3>
 * <pre>docs/mvnw.sh test -Dtest=OrderIntegrationTest</pre>
 *
 * <p><b>为什么把关单任务的首次延迟调到 1 小时</b>：本类是 {@code @Transactional} 的，
 * 数据库改动会回滚，但定时任务跑在<b>另一个线程</b>上，它的事务不受测试事务管辖。
 * 把首次触发推迟到测试结束之后，杜绝这种污染。需要验证关单时，用例内直接调
 * {@link OrderTimeoutTask#closeTimedOutOrders()} —— 在测试线程里执行会加入当前事务、照常回滚。</p>
 */
@SpringBootTest(properties = "mall.order.close-scan-initial-delay-ms=3600000")
@AutoConfigureMockMvc
@Transactional
class OrderIntegrationTest {

    private static final String RAW_PASSWORD = "123456";

    /** 与既有测试保持一致的商品单价 */
    private static final BigDecimal UNIT_PRICE = new BigDecimal("19.90");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderTimeoutTask orderTimeoutTask;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 清理 Redis 状态。
     *
     * <p>数据库改动由事务回滚，<b>Redis 不会</b>。这里要清两类：</p>
     * <ul>
     *   <li>{@code mall:cart:*} —— 下单会读购物车并在成功后清理它；</li>
     *   <li>{@code mall:order:lock:*} —— 下单锁<b>刻意不主动释放</b>（靠 5 秒 TTL），
     *       不清的话会串到后续用例，表现为"莫名其妙 429 请勿重复提交"。</li>
     * </ul>
     */
    @AfterEach
    void cleanRedis() {
        for (String pattern : List.of("mall:cart:*", "mall:order:lock:*", "mall:perm:user:*")) {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
    }

    // ==================================================================
    // 认证
    // ==================================================================

    @Test
    @DisplayName("订单接口必须登录：无令牌直接 401")
    void orderEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/order/list")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/order/20260101abc")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/order/20260101abc/pay")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/order/page")).andExpect(status().isUnauthorized());
    }

    // ==================================================================
    // 下单：购物车结算
    // ==================================================================

    @Test
    @DisplayName("购物车结算：明细存快照、金额服务端算、下单后购物车被清空、库存被扣")
    void checkoutFromCartCreatesOrderWithSnapshotAndClearsCart() throws Exception {
        UserEntity buyer = createUser("t_buyer_");
        String token = login(buyer.getUsername());
        ProductEntity a = createProduct("结算商品A", 1, 100);
        ProductEntity b = createProduct("结算商品B", 1, 100);

        addToCart(token, a.getId(), 2);
        addToCart(token, b.getId(), 1);

        JsonNode body = checkout(token, "张三", "13800001111", "广东省佛山市顺德区某路 1 号", "尽快发货");
        assertThat(body.path("code").asInt())
                .as("下单失败：%s", body.path("message").asText()).isEqualTo(200);

        JsonNode order = body.path("data");
        assertThat(order.path("status").asInt()).isEqualTo(OrderStatus.PENDING.code());
        assertThat(order.path("statusText").asText()).isEqualTo("待支付");
        // 金额全部由服务端算：19.90 × 2 + 19.90 × 1 = 59.70
        assertThat(order.path("totalAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("59.70"));
        assertThat(order.path("payAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("59.70"));
        assertThat(order.path("itemCount").asInt()).isEqualTo(3);
        assertThat(order.path("orderNo").asText()).hasSize(32);
        assertThat(order.path("closeDeadline").asText()).isNotBlank();

        // 明细是快照：名称/单价/小计都在，且小计 = 单价 × 数量
        JsonNode items = order.path("items");
        assertThat(items).hasSize(2);
        JsonNode itemA = itemOf(items, a.getId());
        assertThat(itemA.path("productName").asText()).isEqualTo("结算商品A");
        assertThat(itemA.path("price").decimalValue()).isEqualByComparingTo(UNIT_PRICE);
        assertThat(itemA.path("quantity").asInt()).isEqualTo(2);
        assertThat(itemA.path("subtotal").decimalValue()).isEqualByComparingTo(new BigDecimal("39.80"));

        // 库存被扣：A 100-2=98，B 100-1=99
        assertThat(stockOf(a.getId())).isEqualTo(98);
        assertThat(stockOf(b.getId())).isEqualTo(99);

        // 下单成功后购物车应被清空（被买走的条目已移除）
        assertThat(getJson("/cart", token).path("data").path("totalCount").asInt()).isZero();
    }

    @Test
    @DisplayName("结算只取「已勾选且可购买」的条目：未勾选/已失效的留在车里")
    void checkoutOnlyTakesSelectedAndAvailableItems() throws Exception {
        UserEntity buyer = createUser("t_buyer_");
        String token = login(buyer.getUsername());
        ProductEntity keep = createProduct("已勾选商品", 1, 100);
        ProductEntity unselected = createProduct("未勾选商品", 1, 100);
        ProductEntity invalid = createProduct("稍后下架商品", 1, 100);

        addToCart(token, keep.getId(), 1);
        addToCart(token, unselected.getId(), 1);
        addToCart(token, invalid.getId(), 1);

        // 只勾 keep —— productIds 传空表示整车，这里显式只勾一个
        putJson("/cart/items/selected", token,
                Map.of("productIds", List.of(keep.getId()), "selected", true));
        // keep 勾上之后，把 unselected 取消勾选（加购默认是勾选的）
        putJson("/cart/items/selected", token,
                Map.of("productIds", List.of(unselected.getId()), "selected", false));
        // invalid 在加购之后才下架 —— 结算时它应当是"可购买=false"，被跳过
        invalid.setStatus(0);
        productMapper.updateById(invalid);

        JsonNode order = checkout(token, "李四", "13900002222", "广东省佛山市禅城区某街 2 号", null)
                .path("data");

        assertThat(order.path("totalAmount").decimalValue())
                .as("只结算 keep 一件").isEqualByComparingTo(UNIT_PRICE);
        assertThat(order.path("items")).hasSize(1);

        // 未被结算的两件必须还在车里
        JsonNode cart = getJson("/cart", token).path("data");
        assertThat(cart.path("totalCount").asInt()).as("未勾选与失效的都还在车里").isEqualTo(2);
        // 失效商品只标记不删除
        assertThat(cart.path("invalidCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("购物车里没有勾选任何商品 → 400")
    void checkoutWithEmptySelectionRejected() throws Exception {
        UserEntity buyer = createUser("t_buyer_");
        String token = login(buyer.getUsername());
        ProductEntity product = createProduct("未勾选商品", 1, 100);
        addToCart(token, product.getId(), 1);

        // 取消全选 → 车里没有"已勾选"的条目
        putJson("/cart/items/selected", token, Map.of("selected", false));

        JsonNode body = checkout(token, "王五", "13700003333", "广东省佛山市南海区某道 3 号", null);
        assertThat(body.path("code").asInt()).isEqualTo(400);
        assertThat(body.path("message").asText()).contains("勾选");
        // 下单失败，库存不能被扣
        assertThat(stockOf(product.getId())).isEqualTo(100);
    }

    @Test
    @DisplayName("库存不足 → 400，且不产生订单、库存不变")
    void checkoutInsufficientStockRejected() throws Exception {
        UserEntity buyer = createUser("t_buyer_");
        String token = login(buyer.getUsername());
        ProductEntity product = createProduct("仅剩一件", 1, 1);

        // 加 5 件（加购不校验库存），结算时必然不足
        addToCart(token, product.getId(), 5);

        JsonNode body = checkout(token, "赵六", "13600004444", "广东省佛山市三水区某巷 4 号", null);
        assertThat(body.path("code").asInt()).isEqualTo(400);
        assertThat(body.path("message").asText()).contains("库存不足");

        assertThat(stockOf(product.getId())).as("失败的订单不能扣库存").isEqualTo(1);
        assertThat(orderCountOf(buyer.getId())).as("失败的订单不能落库").isZero();
    }

    // ==================================================================
    // 下单：直接购买
    // ==================================================================

    @Test
    @DisplayName("直接购买：不经过购物车，也不在购物车留痕")
    void buyNowCreatesOrderWithoutTouchingCart() throws Exception {
        UserEntity buyer = createUser("t_buyer_");
        String token = login(buyer.getUsername());
        ProductEntity product = createProduct("直购商品", 1, 100);

        JsonNode order = buyNow(token, product.getId(), 3,
                "孙七", "13500005555", "广东省佛山市高明区某村 5 号", null).path("data");

        assertThat(order.path("totalAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("59.70"));
        assertThat(order.path("itemCount").asInt()).isEqualTo(3);
        assertThat(stockOf(product.getId())).isEqualTo(97);
        // 全程没碰过购物车
        assertThat(getJson("/cart", token).path("data").path("totalCount").asInt()).isZero();
    }

    @Test
    @DisplayName("重复提交被下单锁挡住 → 429（第二次点击不该再下一单）")
    void doubleSubmitIsThrottledByLock() throws Exception {
        UserEntity buyer = createUser("t_buyer_");
        String token = login(buyer.getUsername());
        ProductEntity product = createProduct("防重商品", 1, 100);

        JsonNode first = buyNow(token, product.getId(), 1,
                "周八", "13400006666", "广东省佛山市顺德区某路 6 号", null);
        assertThat(first.path("code").asInt()).isEqualTo(200);

        // 紧接着再来一次 —— 锁刻意不释放，靠 TTL 覆盖"手抖连点"窗口
        JsonNode second = buyNow(token, product.getId(), 1,
                "周八", "13400006666", "广东省佛山市顺德区某路 6 号", null);
        assertThat(second.path("code").asInt()).isEqualTo(429);
        assertThat(orderCountOf(buyer.getId())).as("只应落下一单").isEqualTo(1);
    }

    // ==================================================================
    // 并发：绝不超卖
    // ==================================================================

    /**
     * 并发扣库存。
     *
     * <p><b>为什么这个用例不用 {@code @Transactional}</b>：数据库事务
     * 是线程绑定的，多线程之间互相看不见彼此未提交的数据，也拿不到真正的行锁竞争 ——
     * 那样测出来的"没超卖"是假的。所以这里用 {@code NOT_SUPPORTED} 关掉测试事务，
     * 让每个线程的 UPDATE 真正自动提交，事后用原生 SQL 物理清理。
     * 这也正是它能在本类（类级 {@code @Transactional}）里存在的原因 —— 方法级注解覆盖类级。</p>
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("并发扣减库存：10 个线程抢 5 件，绝不超卖（条件更新在 WHERE 里）")
    void concurrentDeductNeverOversells() throws Exception {
        Long productId = null;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            ProductEntity product = createProduct("并发扣减商品", 1, 5);
            productId = product.getId();
            final Long id = productId;

            int threads = 10;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger success = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        // 每个线程都是独立的自动提交语句 —— 真正在抢同一行的排他锁
                        if (productMapper.deductStock(id, 1) == 1) {
                            success.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("线程池应在 30 秒内结束").isTrue();

            assertThat(success.get())
                    .as("库存 5 件，无论多少并发，成功的扣减必须恰好 5 次").isEqualTo(5);

            Integer remaining = jdbcTemplate.queryForObject(
                    "SELECT stock FROM mall_product WHERE id = ?", Integer.class, productId);
            assertThat(remaining).as("扣完后库存应正好为 0，绝不能为负（超卖）").isZero();
        } finally {
            pool.shutdownNow();
            if (productId != null) {
                // 原生 SQL 物理删除：不能用 productMapper.deleteById（那是逻辑删除，会留下垃圾行）
                jdbcTemplate.update("DELETE FROM mall_product WHERE id = ?", productId);
            }
        }
    }

    // ==================================================================
    // 状态流转：支付
    // ==================================================================

    @Test
    @DisplayName("支付：0→1，销量在支付时累加；重复支付 → 409")
    void paySucceedsThenDoublePayRejected() throws Exception {
        UserEntity buyer = createUser("t_buyer_");
        String token = login(buyer.getUsername());
        ProductEntity product = createProduct("支付商品", 1, 100);

        JsonNode order = buyNow(token, product.getId(), 2,
                "钱九", "13300007777", "广东省佛山市顺德区某路 7 号", null).path("data");
        String orderNo = order.path("orderNo").asText();
        assertThat(productMapper.selectById(product.getId()).getSales())
                .as("下单时销量不应增加").isZero();

        JsonNode paid = postJson("/order/" + orderNo + "/pay", token, null).path("data");
        assertThat(paid.path("status").asInt()).isEqualTo(OrderStatus.PAID.code());
        assertThat(paid.path("payTime").asText()).isNotBlank();

        // 销量在支付成功那一步累加
        assertThat(productMapper.selectById(product.getId()).getSales()).isEqualTo(2);

        // 重复支付 → CAS 失败 → 409
        JsonNode again = postJson("/order/" + orderNo + "/pay", token, null);
        assertThat(again.path("code").asInt()).isEqualTo(409);
        assertThat(productMapper.selectById(product.getId()).getSales())
                .as("重复支付绝不能再加一次销量").isEqualTo(2);
    }

    @Test
    @DisplayName("已支付的订单不能取消 → 409（本轮无退款流程）")
    void paidOrderCannotBeCanceled() throws Exception {
        UserEntity buyer = createUser("t_buyer_");
        String token = login(buyer.getUsername());
        ProductEntity product = createProduct("不可取消商品", 1, 100);

        String orderNo = buyNow(token, product.getId(), 1,
                "吴十", "13200008888", "广东省佛山市顺德区某路 8 号", null)
                .path("data").path("orderNo").asText();
        postJson("/order/" + orderNo + "/pay", token, null);

        JsonNode canceled = postJson("/order/" + orderNo + "/cancel", token, null);
        assertThat(canceled.path("code").asInt()).isEqualTo(409);
        assertThat(stockOf(product.getId())).as("取消失败，库存不能回补").isEqualTo(99);
    }

    // ==================================================================
    // 状态流转：取消与库存回补
    // ==================================================================

    @Test
    @DisplayName("取消：0→4 且回补库存；重复取消 → 409（绝不能重复回补）")
    void cancelRestoresStockAndSecondCancelRejected() throws Exception {
        UserEntity buyer = createUser("t_buyer_");
        String token = login(buyer.getUsername());
        ProductEntity product = createProduct("可取消商品", 1, 100);

        String orderNo = buyNow(token, product.getId(), 4,
                "郑十一", "13100009999", "广东省佛山市顺德区某路 9 号", null)
                .path("data").path("orderNo").asText();
        assertThat(stockOf(product.getId())).isEqualTo(96);

        JsonNode canceled = postJson("/order/" + orderNo + "/cancel", token, null).path("data");
        assertThat(canceled.path("status").asInt()).isEqualTo(OrderStatus.CANCELED.code());
        assertThat(canceled.path("cancelReason").asText()).isEqualTo("买家取消");
        assertThat(canceled.path("cancelTime").asText()).isNotBlank();
        assertThat(stockOf(product.getId())).as("取消后库存应回补到 100").isEqualTo(100);

        // 重复取消：CAS 已经抢不到 → 409，且库存不能再涨
        JsonNode again = postJson("/order/" + orderNo + "/cancel", token, null);
        assertThat(again.path("code").asInt()).isEqualTo(409);
        assertThat(stockOf(product.getId()))
                .as("重复取消若也回补就是库存凭空增加 —— 这正是「先 CAS 再回补」要防的").isEqualTo(100);
    }

    @Test
    @DisplayName("超时关单：任务扫描到过期未支付订单 → 关闭并回补库存")
    void timeoutCloseReleasesStock() throws Exception {
        UserEntity buyer = createUser("t_buyer_");
        String token = login(buyer.getUsername());
        ProductEntity product = createProduct("超时商品", 1, 100);

        Long orderId = Long.valueOf(buyNow(token, product.getId(), 3,
                "冯十二", "13000001010", "广东省佛山市顺德区某路 10 号", null)
                .path("data").path("id").asLong());
        assertThat(stockOf(product.getId())).isEqualTo(97);

        // 把支付截止时间拨到过去，模拟"支付窗口已过"
        OrderEntity order = orderMapper.selectById(orderId);
        order.setCloseDeadline(LocalDateTime.now().minusMinutes(1));
        orderMapper.updateById(order);

        orderTimeoutTask.closeTimedOutOrders();

        OrderEntity after = orderMapper.selectById(orderId);
        assertThat(after.getStatus()).isEqualTo(OrderStatus.CANCELED.code());
        assertThat(after.getCancelReason()).isEqualTo("超时未支付");
        assertThat(stockOf(product.getId())).as("关单必须回补库存，否则库存被永久占住").isEqualTo(100);
    }

    @Test
    @DisplayName("未超时的待支付订单不会被误关")
    void timeoutCloseSkipsNotYetExpired() throws Exception {
        UserEntity buyer = createUser("t_buyer_");
        String token = login(buyer.getUsername());
        ProductEntity product = createProduct("未超时商品", 1, 100);

        String orderNo = buyNow(token, product.getId(), 1,
                "陈十三", "13000001111", "广东省佛山市顺德区某路 11 号", null)
                .path("data").path("orderNo").asText();

        // 关单任务跑一轮：close_deadline 在未来，这一单不该被动
        orderTimeoutTask.closeTimedOutOrders();

        JsonNode still = getJson("/order/" + orderNo, token).path("data");
        assertThat(still.path("status").asInt())
                .as("支付窗口未到就关单，会把正在付款的用户订单砍掉").isEqualTo(OrderStatus.PENDING.code());
        assertThat(stockOf(product.getId())).isEqualTo(99);
    }

    // ==================================================================
    // IDOR / 越权
    // ==================================================================

    @Test
    @DisplayName("访问别人的订单 → 404（不是 403，否则泄露单号真实存在）")
    void buyerCannotSeeOthersOrder() throws Exception {
        UserEntity owner = createUser("t_owner_");
        String ownerToken = login(owner.getUsername());
        ProductEntity product = createProduct("他人商品", 1, 100);
        String orderNo = buyNow(ownerToken, product.getId(), 1,
                "订单主人", "13000001212", "广东省佛山市顺德区某路 12 号", null)
                .path("data").path("orderNo").asText();

        UserEntity intruder = createUser("t_intruder_");
        String intruderToken = login(intruder.getUsername());

        JsonNode detail = getJson("/order/" + orderNo, intruderToken);
        assertThat(detail.path("code").asInt()).as("越权查看必须是 404，不能是 403").isEqualTo(404);

        JsonNode cancel = postJson("/order/" + orderNo + "/cancel", intruderToken, null);
        assertThat(cancel.path("code").asInt()).as("越权取消同样 404").isEqualTo(404);

        // 订单必须毫发无伤
        JsonNode mine = getJson("/order/" + orderNo, ownerToken).path("data");
        assertThat(mine.path("status").asInt()).isEqualTo(OrderStatus.PENDING.code());
        assertThat(stockOf(product.getId())).isEqualTo(99);
    }

    // ==================================================================
    // 平台侧：权限与发货
    // ==================================================================

    @Test
    @DisplayName("平台订单接口需要 order:* 权限：普通用户 403，管理员 200")
    void adminOrderEndpointsEnforcePermission() throws Exception {
        UserEntity normal = createUser("t_normal_");
        String normalToken = login(normal.getUsername());
        JsonNode forbidden = getJson("/admin/order/page", normalToken);
        assertThat(forbidden.path("code").asInt())
                .as("普通用户没有 order:list，必须 403").isEqualTo(403);

        UserEntity admin = createAdmin();
        String adminToken = login(admin.getUsername());
        JsonNode allowed = getJson("/admin/order/page", adminToken);
        assertThat(allowed.path("code").asInt()).isEqualTo(200);
        assertThat(allowed.path("data").path("records").isArray()).isTrue();
    }

    @Test
    @DisplayName("平台发货全流程：支付后发货 2，买家确认收货 3；平台关闭待支付订单")
    void adminShipAndCloseFlow() throws Exception {
        UserEntity admin = createAdmin();
        String adminToken = login(admin.getUsername());

        // ---------- 发货流程 ----------
        UserEntity buyer = createUser("t_buyer_");
        String token = login(buyer.getUsername());
        ProductEntity product = createProduct("发货商品", 1, 100);
        String orderNo = buyNow(token, product.getId(), 1,
                "收货人", "13000001313", "广东省佛山市顺德区某路 13 号", null)
                .path("data").path("orderNo").asText();

        // 未支付就想发货 → 409
        assertThat(postJson("/admin/order/" + orderNo + "/ship", adminToken, null)
                .path("code").asInt()).isEqualTo(409);

        postJson("/order/" + orderNo + "/pay", token, null);
        JsonNode shipped = postJson("/admin/order/" + orderNo + "/ship", adminToken, null).path("data");
        assertThat(shipped.path("status").asInt()).isEqualTo(OrderStatus.SHIPPED.code());
        assertThat(shipped.path("shipTime").asText()).isNotBlank();

        JsonNode finished = postJson("/order/" + orderNo + "/confirm", token, null).path("data");
        assertThat(finished.path("status").asInt()).isEqualTo(OrderStatus.FINISHED.code());

        // 平台可以查任意用户的订单详情
        assertThat(getJson("/admin/order/" + orderNo, adminToken).path("data")
                .path("items")).hasSize(1);

        // ---------- 平台关闭流程 ----------
        UserEntity buyer2 = createUser("t_buyer_");
        String token2 = login(buyer2.getUsername());
        ProductEntity product2 = createProduct("被关闭商品", 1, 100);
        String orderNo2 = buyNow(token2, product2.getId(), 2,
                "收货人", "13000001414", "广东省佛山市顺德区某路 14 号", null)
                .path("data").path("orderNo").asText();
        assertThat(stockOf(product2.getId())).isEqualTo(98);

        JsonNode closed = postJson("/admin/order/" + orderNo2 + "/close", adminToken, null).path("data");
        assertThat(closed.path("status").asInt()).isEqualTo(OrderStatus.CANCELED.code());
        assertThat(closed.path("cancelReason").asText()).isEqualTo("平台关闭");
        assertThat(stockOf(product2.getId())).as("平台关闭同样要回补库存").isEqualTo(100);
    }

    // ==================================================================
    // 我的订单列表
    // ==================================================================

    @Test
    @DisplayName("我的订单列表：只含自己、支持按状态过滤、列表不含明细")
    void pageMyOrdersScopesToSelfAndSupportsStatusFilter() throws Exception {
        UserEntity buyer = createUser("t_buyer_");
        String token = login(buyer.getUsername());
        ProductEntity a = createProduct("列表商品A", 1, 100);
        ProductEntity b = createProduct("列表商品B", 1, 100);

        String pending = buyNow(token, a.getId(), 1,
                "列表人", "13000001515", "广东省佛山市顺德区某路 15 号", null)
                .path("data").path("orderNo").asText();
        // 下单锁刻意不主动释放（5 秒 TTL），同一用户连续下第二单前必须手动清掉，
        // 否则第二单会被误判为"重复提交"返回 429。这是测试专用的动作，
        // 生产环境本就该让用户等 5 秒。
        clearOrderLock(buyer.getId());
        String paid = buyNow(token, b.getId(), 1,
                "列表人", "13000001515", "广东省佛山市顺德区某路 15 号", null)
                .path("data").path("orderNo").asText();
        postJson("/order/" + paid + "/pay", token, null);

        // 全部：两单
        JsonNode all = getJson("/order/list", token).path("data");
        assertThat(all.path("total").asInt()).isEqualTo(2);
        assertThat(all.path("records").get(0).path("items").isArray())
                .as("列表接口不回明细（items 应为 null 或缺失，而不是数组）").isFalse();

        // 只看待支付：只剩 pending 那单
        JsonNode onlyPending = getJson("/order/list?status=" + OrderStatus.PENDING.code(), token)
                .path("data");
        assertThat(onlyPending.path("total").asInt()).isEqualTo(1);
        assertThat(onlyPending.path("records").get(0).path("orderNo").asText()).isEqualTo(pending);

        // 别的用户看不到我的订单
        UserEntity other = createUser("t_other_");
        String otherToken = login(other.getUsername());
        assertThat(getJson("/order/list", otherToken).path("data").path("total").asInt()).isZero();
    }

    // ==================================================================
    // 测试工具
    // ==================================================================

    private UserEntity createUser(String prefix) {
        UserEntity user = new UserEntity();
        user.setUsername(prefix + UUID.randomUUID().toString().substring(0, 8));
        user.setPassword(passwordEncoder.encode(RAW_PASSWORD));
        user.setNickname(prefix);
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }

    /** 建一个拥有 ADMIN 角色的用户（自动刷权限缓存）。 */
    private UserEntity createAdmin() {
        UserEntity admin = createUser("t_admin_");
        RoleEntity role = roleMapper.selectOne(
                Wrappers.<RoleEntity>lambdaQuery().eq(RoleEntity::getCode, "ADMIN"));
        assertThat(role).as("角色 ADMIN 不存在，请先执行 06_mall_rbac.sql").isNotNull();

        UserRoleEntity relation = new UserRoleEntity();
        relation.setUserId(admin.getId());
        relation.setRoleId(role.getId());
        userRoleMapper.insert(relation);
        return admin;
    }

    private ProductEntity createProduct(String name, int status, int stock) {
        ProductEntity product = new ProductEntity();
        product.setName(name);
        product.setSubtitle("测试用");
        product.setPrice(UNIT_PRICE);
        product.setOriginalPrice(new BigDecimal("29.90"));
        product.setStock(stock);
        product.setSales(0);
        product.setStatus(status);
        // 0 = 平台自营（绝不用 null）
        product.setSellerId(0L);
        product.setDeleted(0L);
        productMapper.insert(product);
        return product;
    }

    private int stockOf(Long productId) {
        ProductEntity product = productMapper.selectById(productId);
        assertThat(product).as("商品 %s 应存在", productId).isNotNull();
        return product.getStock();
    }

    private long orderCountOf(Long userId) {
        // 直接 count 数据库里的真实行数，而不是信接口返回的数量
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mall_order WHERE user_id = ? AND deleted = 0",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    private JsonNode itemOf(JsonNode items, Long productId) {
        for (JsonNode item : items) {
            if (item.path("productId").asLong() == productId) {
                return item;
            }
        }
        throw new AssertionError("订单明细里找不到商品 " + productId);
    }

    private JsonNode addToCart(String token, Long productId, int quantity) throws Exception {
        return postJson("/cart/items", token, Map.of("productId", productId, "quantity", quantity));
    }

    /**
     * 手动清掉某用户的下单锁。
     *
     * <p>下单锁是"防手抖连点"的优化，刻意不主动释放、靠 5 秒 TTL 自然过期。
     * 于是"同一用户在同一个用例里连下两单"会被它挡住。测试需要在连续下单前显式清除，
     * 生产环境则本就该让用户等那 5 秒 —— 所以这个方法只存在于测试里。</p>
     */
    private void clearOrderLock(Long userId) {
        redisTemplate.delete("mall:order:lock:" + userId);
    }

    private JsonNode checkout(String token, String name, String phone, String address, String remark)
            throws Exception {
        Map<String, Object> body = receiverBody(name, phone, address, remark);
        return postJson("/order/checkout", token, body);
    }

    private JsonNode buyNow(String token, Long productId, int quantity,
                            String name, String phone, String address, String remark) throws Exception {
        Map<String, Object> body = receiverBody(name, phone, address, remark);
        body.put("productId", productId);
        body.put("quantity", quantity);
        return postJson("/order/buy-now", token, body);
    }

    /** remark 可空，所以用 HashMap 而不是 Map.of（后者不允许 null 值）。 */
    private Map<String, Object> receiverBody(String name, String phone, String address, String remark) {
        Map<String, Object> body = new HashMap<>();
        body.put("receiverName", name);
        body.put("receiverPhone", phone);
        body.put("receiverAddress", address);
        if (remark != null) {
            body.put("remark", remark);
        }
        return body;
    }

    private String login(String username) throws Exception {
        JsonNode body = postJson("/user/login", null, Map.of(
                "username", username, "password", RAW_PASSWORD));
        assertThat(body.path("code").asInt())
                .as("登录失败：%s", body.path("message").asText()).isEqualTo(200);
        return body.path("data").path("token").asText();
    }

    private JsonNode getJson(String url, String token) throws Exception {
        var request = get(url);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return readJson(mockMvc.perform(request).andReturn());
    }

    private JsonNode postJson(String url, String token, Object body) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : objectMapper.writeValueAsString(body));
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return readJson(mockMvc.perform(request).andReturn());
    }

    private JsonNode putJson(String url, String token, Object body) throws Exception {
        var request = put(url).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : objectMapper.writeValueAsString(body));
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return readJson(mockMvc.perform(request).andReturn());
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
