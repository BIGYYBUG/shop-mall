package com.mall;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.entity.CartItemEntity;
import com.mall.entity.ProductEntity;
import com.mall.entity.UserEntity;
import com.mall.mapper.CartItemMapper;
import com.mall.mapper.ProductMapper;
import com.mall.mapper.UserMapper;
import com.mall.service.cart.CartFlushTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 购物车集成测试。
 *
 * <h3>这一组用例真正想守住的东西</h3>
 *
 * <p>购物车的存储方案是「Redis 主存 + MySQL 异步落库」，它的风险<b>不在</b>接口能不能调通，
 * 而在下面这几条不变量上：</p>
 * <ol>
 *   <li><b>加购是累加而不是覆盖</b> —— 覆盖式写不会报错，只是数量悄悄不对
 *       （{@link #addAccumulatesQuantityInsteadOfOverwriting}）</li>
 *   <li><b>失效商品只打标记、不被静默删除</b> —— 静默删会让用户以为东西丢了
 *       （{@link #invalidProductIsMarkedButNotRemoved}）</li>
 *   <li><b>Redis 里没有车 ≠ 车是空的</b> —— 前者要回源 MySQL 重建，
 *       后者才是真的空（{@link #redisLossRebuildsCartFromMysql}）</li>
 *   <li><b>刷库绝不能拿"空车"覆盖 MySQL</b> —— 这是最隐蔽的数据丢失路径：
 *       Redis 一旦过期/重启，一轮错误的刷库就能把备份抹掉
 *       （{@link #flushMustNotWipeMysqlWhenRedisCartMissing}）</li>
 *   <li><b>重复加购只留一行</b> —— 唯一键 + UPSERT 是否真的生效
 *       （{@link #flushKeepsSingleRowPerProduct}）</li>
 * </ol>
 *
 * <h3>前置条件</h3>
 * 本机 MySQL（mall 库，需已执行 09_mall_cart.sql）与 Redis 需处于运行状态。
 *
 * <h3>运行方式</h3>
 * <pre>docs/mvnw.sh test -Dtest=CartIntegrationTest</pre>
 *
 * <p><b>为什么把落库任务的首次延迟调到 1 小时</b>：本类是 {@code @Transactional} 的，
 * 数据库改动会回滚，但定时任务跑在<b>另一个线程</b>上，它的写入落在测试事务之外、
 * 不会被回滚。把首次触发推迟到测试结束之后，就杜绝了这种污染。
 * 需要验证落库时，用例内直接调 {@link CartFlushTask#flushPending()} —— 在测试线程里
 * 执行会加入当前事务、照常回滚。</p>
 */
@SpringBootTest(properties = "mall.cart.flush-initial-delay-ms=3600000")
@AutoConfigureMockMvc
@Transactional
class CartIntegrationTest {

    private static final String RAW_PASSWORD = "123456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private CartItemMapper cartItemMapper;

    @Autowired
    private CartFlushTask cartFlushTask;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 清空购物车相关的 Redis key。
     *
     * <p>和 RBAC / 卖家测试同样的理由：数据库改动由事务回滚，<b>Redis 不会</b>。
     * 购物车更是全部状态都在 Redis 里，不清就必然污染后续用例。</p>
     */
    @AfterEach
    void cleanRedis() {
        Set<String> keys = redisTemplate.keys("mall:cart:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // ==================================================================
    // 权限
    // ==================================================================

    @Test
    @DisplayName("购物车接口必须登录：无令牌直接 401")
    void cartRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/cart")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/cart/count")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/cart/items")).andExpect(status().isUnauthorized());
    }

    // ==================================================================
    // 加购
    // ==================================================================

    @Test
    @DisplayName("重复加购是累加，不是覆盖")
    void addAccumulatesQuantityInsteadOfOverwriting() throws Exception {
        String token = login(newUser());
        ProductEntity product = createProduct("加购累加商品", 1, 100);

        add(token, product.getId(), 2);
        JsonNode cart = add(token, product.getId(), 3);

        JsonNode item = onlyItem(cart);
        assertThat(item.path("quantity").asInt()).as("2 + 3 应累加为 5").isEqualTo(5);
    }

    @Test
    @DisplayName("加购返回实时价格与小计，金额由服务端算")
    void addReturnsRealtimePriceAndSubtotal() throws Exception {
        String token = login(newUser());
        ProductEntity product = createProduct("计价商品", 1, 100);

        JsonNode item = onlyItem(add(token, product.getId(), 3));

        assertThat(item.path("price").decimalValue()).isEqualByComparingTo(new BigDecimal("19.90"));
        assertThat(item.path("subtotal").decimalValue())
                .as("小计应为 19.90 × 3 = 59.70").isEqualByComparingTo(new BigDecimal("59.70"));
    }

    @Test
    @DisplayName("下架商品无法加入购物车")
    void cannotAddOffShelfProduct() throws Exception {
        String token = login(newUser());
        ProductEntity offShelf = createProduct("已下架商品", 0, 100);

        JsonNode body = postJson("/cart/items", token, Map.of(
                "productId", offShelf.getId(), "quantity", 1));
        assertThat(body.path("code").asInt()).as("应被业务规则拒绝").isNotEqualTo(200);
    }

    // ==================================================================
    // 改量 / 勾选 / 删除
    // ==================================================================

    @Test
    @DisplayName("改数量是设为指定值；批量勾选与全选能正确驱动合计")
    void updateQuantityAndSelectionDriveTotals() throws Exception {
        String token = login(newUser());
        ProductEntity a = createProduct("商品A", 1, 100);
        ProductEntity b = createProduct("商品B", 1, 100);

        add(token, a.getId(), 1);
        add(token, b.getId(), 1);

        // 改量：a 设为 2
        JsonNode cart = putJson("/cart/items/" + a.getId(), token, Map.of("quantity", 2));
        assertThat(quantityOf(cart, a.getId())).isEqualTo(2);

        // 取消全选 → 合计归零
        cart = putJson("/cart/items/selected", token, Map.of("selected", false));
        assertThat(cart.path("data").path("selectedAmount").decimalValue())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cart.path("data").path("allSelected").asBoolean()).isFalse();

        // 只勾 a → 合计 = 19.90 × 2
        cart = putJson("/cart/items/selected", token,
                Map.of("productIds", List.of(a.getId()), "selected", true));
        assertThat(cart.path("data").path("selectedAmount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("39.80"));
        assertThat(cart.path("data").path("totalQuantity").asInt()).isEqualTo(2);

        // 全选（productIds 传空表示整车）→ 合计 = 39.80 + 19.90
        cart = putJson("/cart/items/selected", token, Map.of("selected", true));
        assertThat(cart.path("data").path("selectedAmount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("59.70"));
        assertThat(cart.path("data").path("allSelected").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("移除单项与清空")
    void removeAndClear() throws Exception {
        String token = login(newUser());
        ProductEntity a = createProduct("删除商品A", 1, 100);
        ProductEntity b = createProduct("删除商品B", 1, 100);
        add(token, a.getId(), 1);
        add(token, b.getId(), 1);

        JsonNode afterRemove = deleteJson("/cart/items/" + a.getId(), token);
        assertThat(afterRemove.path("data").path("totalCount").asInt()).isEqualTo(1);

        deleteJson("/cart/items", token);
        assertThat(getJson("/cart", token).path("data").path("totalCount").asInt()).isZero();
        assertThat(getJson("/cart/count", token).path("data").asInt()).isZero();
    }

    // ==================================================================
    // 失效商品
    // ==================================================================

    @Test
    @DisplayName("商品下架后，购物车里只打失效标记，不静默删除")
    void invalidProductIsMarkedButNotRemoved() throws Exception {
        String token = login(newUser());
        ProductEntity product = createProduct("稍后下架的商品", 1, 100);
        add(token, product.getId(), 2);

        // 商品在加购之后才下架
        product.setStatus(0);
        productMapper.updateById(product);

        JsonNode cart = getJson("/cart", token);
        JsonNode item = onlyItem(cart);

        assertThat(item.path("available").asBoolean()).isFalse();
        assertThat(item.path("invalidReason").asText()).contains("下架");
        assertThat(cart.path("data").path("invalidCount").asInt()).isEqualTo(1);
        assertThat(cart.path("data").path("totalCount").asInt())
                .as("失效商品必须还在车里，不能消失").isEqualTo(1);
        assertThat(cart.path("data").path("selectedAmount").decimalValue())
                .as("失效商品不计入合计，否则结算时金额会突然变小")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ==================================================================
    // 落库与自愈
    // ==================================================================

    @Test
    @DisplayName("落库把 Redis 内容写进 MySQL，且同一商品只留一行")
    void flushKeepsSingleRowPerProduct() throws Exception {
        UserEntity user = newUser();
        String token = login(user);
        ProductEntity product = createProduct("落库商品", 1, 100);

        add(token, product.getId(), 2);
        add(token, product.getId(), 3);

        cartFlushTask.flushPending();

        List<CartItemEntity> rows = rowsOf(user.getId());
        assertThat(rows).as("同一 (user, product) 只能有一行 —— 唯一键 + UPSERT 是否真的生效")
                .hasSize(1);
        assertThat(rows.get(0).getQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("Redis 里车丢了（过期/重启）→ 自动回源 MySQL 重建")
    void redisLossRebuildsCartFromMysql() throws Exception {
        UserEntity user = newUser();
        String token = login(user);
        ProductEntity product = createProduct("自愈商品", 1, 100);

        add(token, product.getId(), 4);
        cartFlushTask.flushPending();

        // 模拟 Redis 过期/重启：直接把车删掉（MySQL 里的备份还在）
        redisTemplate.delete("mall:cart:" + user.getId());
        assertThat(redisTemplate.hasKey("mall:cart:" + user.getId())).isFalse();

        JsonNode cart = getJson("/cart", token);
        assertThat(cart.path("data").path("totalCount").asInt())
                .as("应从 MySQL 回源重建，而不是显示空车").isEqualTo(1);
        assertThat(quantityOf(cart, product.getId())).isEqualTo(4);
        assertThat(redisTemplate.hasKey("mall:cart:" + user.getId()))
                .as("重建后 Redis 里应当重新有这辆车").isTrue();
    }

    @Test
    @DisplayName("刷库时若 Redis 里已无该车，绝不能拿空车覆盖 MySQL")
    void flushMustNotWipeMysqlWhenRedisCartMissing() throws Exception {
        UserEntity user = newUser();
        String token = login(user);
        ProductEntity product = createProduct("防误删商品", 1, 100);

        add(token, product.getId(), 1);
        cartFlushTask.flushPending();
        assertThat(rowsOf(user.getId())).hasSize(1);

        // 车在 Redis 里消失（过期/重启），但脏集合里还留着这个用户
        redisTemplate.delete("mall:cart:" + user.getId());
        redisTemplate.opsForSet().add("mall:cart:dirty", String.valueOf(user.getId()));

        cartFlushTask.flushPending();

        assertThat(rowsOf(user.getId()))
                .as("Redis 无车时必须以 MySQL 为准，否则一轮刷库就把备份抹掉了")
                .hasSize(1);
    }

    // ==================================================================
    // 测试工具
    // ==================================================================

    private UserEntity newUser() {
        UserEntity user = new UserEntity();
        user.setUsername("cart" + UUID.randomUUID().toString().substring(0, 8));
        user.setPassword(passwordEncoder.encode(RAW_PASSWORD));
        user.setNickname("cart-user");
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }

    private ProductEntity createProduct(String name, int status, int stock) {
        ProductEntity product = new ProductEntity();
        product.setName(name);
        product.setSubtitle("测试用");
        product.setPrice(new BigDecimal("19.90"));
        product.setOriginalPrice(new BigDecimal("29.90"));
        product.setStock(stock);
        product.setSales(0);
        product.setStatus(status);
        // 0 = 平台自营（绝不用 null，否则卖家侧条件查询永远不命中）
        product.setSellerId(0L);
        product.setDeleted(0L);
        productMapper.insert(product);
        return product;
    }

    private List<CartItemEntity> rowsOf(Long userId) {
        return cartItemMapper.selectList(
                Wrappers.<CartItemEntity>lambdaQuery().eq(CartItemEntity::getUserId, userId));
    }

    private JsonNode add(String token, Long productId, int quantity) throws Exception {
        return postJson("/cart/items", token,
                Map.of("productId", productId, "quantity", quantity));
    }

    private JsonNode onlyItem(JsonNode cart) {
        JsonNode items = cart.path("data").path("items");
        assertThat(items.isArray()).as("items 应当是数组").isTrue();
        assertThat(items.size()).as("购物车应当只有一件商品").isEqualTo(1);
        return items.get(0);
    }

    private int quantityOf(JsonNode cart, Long productId) {
        for (JsonNode item : cart.path("data").path("items")) {
            if (item.path("productId").asLong() == productId) {
                return item.path("quantity").asInt();
            }
        }
        throw new AssertionError("购物车中找不到商品 " + productId);
    }

    private String login(UserEntity user) throws Exception {
        JsonNode body = postJson("/user/login", null, Map.of(
                "username", user.getUsername(), "password", RAW_PASSWORD));
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

    private JsonNode putJson(String url, String token, Object body) throws Exception {
        var request = put(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return readJson(mockMvc.perform(request).andReturn());
    }

    private JsonNode postJson(String url, String token, Object body) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return readJson(mockMvc.perform(request).andReturn());
    }

    private JsonNode deleteJson(String url, String token) throws Exception {
        var request = delete(url);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return readJson(mockMvc.perform(request).andReturn());
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
