package com.mall;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.entity.ProductEntity;
import com.mall.entity.RoleEntity;
import com.mall.entity.ShopEntity;
import com.mall.entity.ShopStatus;
import com.mall.entity.UserEntity;
import com.mall.entity.UserRoleEntity;
import com.mall.mapper.ProductMapper;
import com.mall.mapper.RoleMapper;
import com.mall.mapper.ShopMapper;
import com.mall.mapper.UserMapper;
import com.mall.mapper.UserRoleMapper;
import com.mall.service.rbac.PermissionService;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 卖家体系集成测试：店铺入驻 → 审核 → 经营 → 冻结。
 *
 * <h3>这一组用例真正想守住的东西</h3>
 *
 * <p>不是"接口能不能调通"，而是那条跨越 shop 与 rbac 两个领域的不变量：</p>
 * <pre>
 *   SELLER 角色  ⟺  shop.status == 1（正常）
 * </pre>
 *
 * <p>它有三个容易分别失守的方向，本类各有用例覆盖：</p>
 * <ol>
 *   <li><b>审核通过却忘了授角色</b> → 店主有店铺但没有 {@code seller:*} 权限，
 *       所有卖家接口 403（{@link #approvedShopGetsSellerRoleAndCanCreateProduct}）</li>
 *   <li><b>驳回/冻结却忘了收角色</b> → 身份档案已变更、权限却还留着，
 *       店被冻结了还能改商品（{@link #frozenShopTakesProductsOfflineAndRevokesRole}）</li>
 *   <li><b>只看权限不做归属校验</b> → 卖家能改别人的商品（
 *       {@link #sellerCannotOperateOthersProduct}）</li>
 * </ol>
 *
 * <h3>前置条件</h3>
 * 本机 MySQL（mall 库，需已执行 03 / 06 / 07 三个脚本）与 Redis 需处于运行状态。
 *
 * <h3>运行方式</h3>
 * <pre>docs/mvnw.sh test -Dtest=ShopIntegrationTest</pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ShopIntegrationTest {

    private static final String RAW_PASSWORD = "123456";

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
    private ShopMapper shopMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 清空本项目的权限缓存命名空间。
     *
     * <p>与 {@code RbacIntegrationTest} 同样的理由：数据库改动由事务回滚，
     * Redis 不会。而「审核店铺」会刷新店主（乃至角色下所有人）的缓存，
     * 这些缓存保留着事务内的快照，会污染后续用例。</p>
     */
    @AfterEach
    void cleanRedisCache() {
        Set<String> permKeys = redisTemplate.keys("mall:perm:user:*");
        if (permKeys != null && !permKeys.isEmpty()) {
            redisTemplate.delete(permKeys);
        }
        Set<String> roleKeys = redisTemplate.keys("mall:role:user:*");
        if (roleKeys != null && !roleKeys.isEmpty()) {
            redisTemplate.delete(roleKeys);
        }
    }

    // ==================================================================
    // 入驻申请
    // ==================================================================

    @Test
    @DisplayName("申请入驻后店铺处于待审核，且店主还没有 SELLER 角色")
    void applyShopCreatesPendingShop() throws Exception {
        UserEntity seller = createUser("t_seller_");
        String token = login(seller.getUsername());

        JsonNode applied = apply(token, "测试小店");
        assertThat(applied.path("code").asInt()).isEqualTo(200);

        JsonNode mine = getJson("/seller/shop/mine", token);
        assertThat(mine.path("data").path("status").asInt()).isEqualTo(ShopStatus.PENDING);
        assertThat(mine.path("data").path("statusText").asText()).isEqualTo("待审核");

        // 关键：申请入驻不该顺手把卖家权限发出去
        assertThat(roleMapper.selectCodesByUserId(seller.getId())).doesNotContain("SELLER");
    }

    @Test
    @DisplayName("重复申请入驻 → 400")
    void duplicateApplyShouldFail() throws Exception {
        UserEntity seller = createUser("t_seller_");
        String token = login(seller.getUsername());
        assertThat(apply(token, "第一家店").path("code").asInt()).isEqualTo(200);

        JsonNode again = apply(token, "第二家店");
        assertThat(again.path("code").asInt()).isEqualTo(400);
        assertThat(again.path("message").asText()).contains("已提交");
    }

    @Test
    @DisplayName("被驳回的店铺可以改资料重新提交，状态回到待审核且旧驳回原因被清空")
    void rejectedShopCanReapply() throws Exception {
        UserEntity admin = createUser("t_admin_");
        linkRole(admin.getId(), "ADMIN");
        String adminToken = login(admin.getUsername());

        UserEntity seller = createUser("t_seller_");
        String sellerToken = login(seller.getUsername());
        apply(sellerToken, "资料不全的店");

        // 驳回
        JsonNode rejected = audit(adminToken, shopIdOf(seller.getId()), ShopStatus.REJECTED, "缺少经营资质");
        assertThat(rejected.path("code").asInt()).isEqualTo(200);
        JsonNode mine = getJson("/seller/shop/mine", sellerToken);
        assertThat(mine.path("data").path("status").asInt()).isEqualTo(ShopStatus.REJECTED);
        assertThat(mine.path("data").path("rejectReason").asText()).isEqualTo("缺少经营资质");

        // 重新提交（复用 apply 接口）
        JsonNode reapplied = apply(sellerToken, "补全资料后的店");
        assertThat(reapplied.path("code").asInt()).isEqualTo(200);

        JsonNode after = getJson("/seller/shop/mine", sellerToken);
        assertThat(after.path("data").path("status").asInt()).isEqualTo(ShopStatus.PENDING);
        // 旧驳回原因必须被清掉，否则界面上会一直挂着上一次的理由，店主以为没提交成功
        assertThat(after.path("data").path("rejectReason").isNull()
                || after.path("data").path("rejectReason").asText().isEmpty()).isTrue();
    }

    // ==================================================================
    // 审核与角色联动
    // ==================================================================

    @Test
    @DisplayName("审核通过 → 店主拿到 SELLER 角色，用「旧令牌」即可创建商品且归属正确")
    void approvedShopGetsSellerRoleAndCanCreateProduct() throws Exception {
        UserEntity admin = createUser("t_admin_");
        linkRole(admin.getId(), "ADMIN");
        String adminToken = login(admin.getUsername());

        UserEntity seller = createUser("t_seller_");
        String sellerToken = login(seller.getUsername());
        apply(sellerToken, "正规小店");

        // 审核前：没有 seller:product:create 权限，被切面拦住
        assertThat(createProduct(sellerToken, "审核前的商品").path("code").asInt()).isEqualTo(403);

        assertThat(audit(adminToken, shopIdOf(seller.getId()), ShopStatus.ACTIVE, null)
                .path("code").asInt()).isEqualTo(200);

        // 角色确实落库
        assertThat(roleMapper.selectCodesByUserId(seller.getId())).contains("SELLER");

        // 关键：登录是审核之前做的，用的还是那枚旧令牌。
        // 权限走 Redis 而非 JWT，所以角色一变、缓存一刷，旧令牌立刻获得新能力。
        JsonNode created = createProduct(sellerToken, "审核后的商品");
        assertThat(created.path("code").asInt()).isEqualTo(200);
        Long productId = created.path("data").asLong();

        // 归属必须由服务端写成店主自己，客户端无从指定
        ProductEntity saved = productMapper.selectById(productId);
        assertThat(saved.getSellerId()).isEqualTo(seller.getId());
        assertThat(saved.getStatus()).isZero();  // 新商品默认下架
    }

    @Test
    @DisplayName("驳回店铺会收回 SELLER 角色，店主立刻失去卖家接口权限")
    void rejectedShopLosesSellerRole() throws Exception {
        UserEntity admin = createUser("t_admin_");
        linkRole(admin.getId(), "ADMIN");
        String adminToken = login(admin.getUsername());

        UserEntity seller = createUser("t_seller_");
        String sellerToken = login(seller.getUsername());
        apply(sellerToken, "先通过再驳回的店");
        Long shopId = shopIdOf(seller.getId());

        assertThat(audit(adminToken, shopId, ShopStatus.ACTIVE, null).path("code").asInt()).isEqualTo(200);
        assertThat(createProduct(sellerToken, "临时商品").path("code").asInt()).isEqualTo(200);

        // 驳回
        assertThat(audit(adminToken, shopId, ShopStatus.REJECTED, "资质过期")
                .path("code").asInt()).isEqualTo(200);

        assertThat(roleMapper.selectCodesByUserId(seller.getId())).doesNotContain("SELLER");
        // 权限被收回后，同一枚令牌立刻 403 —— 这正是"身份与权限必须联动"的验证点
        assertThat(createProduct(sellerToken, "驳回后还想建").path("code").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("冻结店铺：收回 SELLER 角色，并把名下在售商品全部下架")
    void frozenShopTakesProductsOfflineAndRevokesRole() throws Exception {
        UserEntity admin = createUser("t_admin_");
        linkRole(admin.getId(), "ADMIN");
        String adminToken = login(admin.getUsername());

        UserEntity seller = createUser("t_seller_");
        String sellerToken = login(seller.getUsername());
        apply(sellerToken, "违规小店");
        Long shopId = shopIdOf(seller.getId());
        audit(adminToken, shopId, ShopStatus.ACTIVE, null);

        Long productId = createProduct(sellerToken, "在售商品").path("data").asLong();
        assertThat(putJson("/seller/product/" + productId + "/status?status=1", sellerToken, null)
                .path("code").asInt()).isEqualTo(200);
        assertThat(productMapper.selectById(productId).getStatus()).isEqualTo(1);

        // 冻结
        assertThat(audit(adminToken, shopId, ShopStatus.FROZEN, "多次违规")
                .path("code").asInt()).isEqualTo(200);

        // ① 角色被收回
        assertThat(roleMapper.selectCodesByUserId(seller.getId())).doesNotContain("SELLER");

        // ② 商品被下架 —— 只改店铺状态而不管商品，等于平台冻结了店铺却还在替它出货
        assertThat(productMapper.selectById(productId).getStatus())
                .as("冻结店铺后，名下在售商品必须被下架")
                .isZero();

        // ③ 卖家接口已不可用
        assertThat(createProduct(sellerToken, "冻结后还想建").path("code").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("审核结果不能传「待审核」，驳回必须填原因")
    void auditInputValidation() throws Exception {
        UserEntity admin = createUser("t_admin_");
        linkRole(admin.getId(), "ADMIN");
        String adminToken = login(admin.getUsername());

        UserEntity seller = createUser("t_seller_");
        String sellerToken = login(seller.getUsername());
        apply(sellerToken, "校验用店铺");
        Long shopId = shopIdOf(seller.getId());

        // status=0 被 @Min(1) 挡住
        assertThat(audit(adminToken, shopId, ShopStatus.PENDING, null).path("code").asInt()).isEqualTo(400);

        // 驳回不填原因是跨字段规则，由服务层拦住
        JsonNode noReason = audit(adminToken, shopId, ShopStatus.REJECTED, null);
        assertThat(noReason.path("code").asInt()).isEqualTo(400);
        assertThat(noReason.path("message").asText()).contains("驳回原因");
    }

    // ==================================================================
    // 归属校验：卖家只能碰自己的货
    // ==================================================================

    @Test
    @DisplayName("卖家的商品列表只包含自己的商品")
    void sellerSeesOnlyOwnProducts() throws Exception {
        UserEntity admin = createUser("t_admin_");
        linkRole(admin.getId(), "ADMIN");
        String adminToken = login(admin.getUsername());

        Long sellerA = approvedSeller(adminToken, "t_sellerA_", "A 的店");
        Long sellerB = approvedSeller(adminToken, "t_sellerB_", "B 的店");

        createProduct(tokenOfUser(sellerA), "A 的商品一");
        createProduct(tokenOfUser(sellerA), "A 的商品二");
        createProduct(tokenOfUser(sellerB), "B 的商品");

        JsonNode pageA = getJson("/seller/product/page?pageSize=100", tokenOfUser(sellerA));
        assertThat(pageA.path("code").asInt()).isEqualTo(200);
        assertThat(pageA.path("data").path("total").asLong()).isEqualTo(2);
        pageA.path("data").path("records").forEach(item ->
                assertThat(item.path("sellerId").asLong()).isEqualTo(sellerA));
    }

    @Test
    @DisplayName("卖家操作他人商品 → 404，且商品内容不被改动")
    void sellerCannotOperateOthersProduct() throws Exception {
        UserEntity admin = createUser("t_admin_");
        linkRole(admin.getId(), "ADMIN");
        String adminToken = login(admin.getUsername());

        Long sellerA = approvedSeller(adminToken, "t_sellerA_", "A 的店");
        Long sellerB = approvedSeller(adminToken, "t_sellerB_", "B 的店");
        String tokenA = tokenOfUser(sellerA);

        Long productOfB = createProduct(tokenOfUser(sellerB), "B 的宝贝").path("data").asLong();

        // 详情 / 修改 / 下架 / 删除，四条路径都要挡住
        assertThat(getJson("/seller/product/" + productOfB, tokenA).path("code").asInt()).isEqualTo(404);

        JsonNode update = putJson("/seller/product/" + productOfB, tokenA, productJson("被篡改的名字"));
        assertThat(update.path("code").asInt())
                .as("越权修改必须失败，否则卖家改一个 id 就能动别人的货")
                .isEqualTo(404);

        assertThat(putJson("/seller/product/" + productOfB + "/status?status=0", tokenA, null)
                .path("code").asInt()).isEqualTo(404);

        // 商品内容一个字都没变
        assertThat(productMapper.selectById(productOfB).getName()).isEqualTo("B 的宝贝");
        assertThat(productMapper.selectById(productOfB).getSellerId()).isEqualTo(sellerB);
    }

    @Test
    @DisplayName("未通过审核的店铺无法管理商品：待审核 → 403")
    void pendingShopCannotManageProducts() throws Exception {
        UserEntity seller = createUser("t_seller_");
        String token = login(seller.getUsername());
        apply(token, "还没审核的店");

        // 此时既没有 seller 权限（切面拦），即便直接给权限也会被店铺状态拦（服务层拦）
        assertThat(createProduct(token, "抢跑的商品").path("code").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("普通用户（无 SELLER 角色）访问卖家接口 → 403")
    void plainUserCannotAccessSellerEndpoints() throws Exception {
        UserEntity plain = createUser("t_plain_");
        linkRole(plain.getId(), "USER");
        String token = login(plain.getUsername());

        assertThat(getJson("/seller/product/page", token).path("code").asInt()).isEqualTo(403);
        assertThat(getJson("/seller/shop/mine", token).path("code").asInt()).isEqualTo(404); // 没店铺
        assertThat(createProduct(token, "普通用户的商品").path("code").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("未登录访问卖家接口 → 401（/seller/** 不在放行清单里）")
    void sellerEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/seller/product/page"))
                .andExpect(status().isUnauthorized());
    }

    // ==================================================================
    // 平台侧
    // ==================================================================

    @Test
    @DisplayName("店铺分页：待审核的排在最前面，且带出店主登录名")
    void adminShopPagePutsPendingFirst() throws Exception {
        UserEntity admin = createUser("t_admin_");
        linkRole(admin.getId(), "ADMIN");
        String adminToken = login(admin.getUsername());

        UserEntity approvedSeller = createUser("t_seller_");
        apply(login(approvedSeller.getUsername()), "已通过的店");
        audit(adminToken, shopIdOf(approvedSeller.getId()), ShopStatus.ACTIVE, null);

        UserEntity pendingSeller = createUser("t_seller_");
        apply(login(pendingSeller.getUsername()), "待审核的店");

        JsonNode page = getJson("/admin/shop/page?pageSize=100", adminToken);
        assertThat(page.path("code").asInt()).isEqualTo(200);

        JsonNode records = page.path("data").path("records");
        assertThat(records.size()).isGreaterThanOrEqualTo(2);
        // 第一条应当是待审核的
        assertThat(records.get(0).path("status").asInt()).isEqualTo(ShopStatus.PENDING);
        // 店主登录名要带出来，否则审核界面只看到一堆 userId 没法用
        assertThat(records.get(0).path("ownerUsername").asText()).startsWith("t_seller_");
    }

    @Test
    @DisplayName("店主修改自己店铺资料不会改变审核状态")
    void ownerUpdateShouldNotChangeStatus() throws Exception {
        UserEntity admin = createUser("t_admin_");
        linkRole(admin.getId(), "ADMIN");
        String adminToken = login(admin.getUsername());

        UserEntity seller = createUser("t_seller_");
        String sellerToken = login(seller.getUsername());
        apply(sellerToken, "改名前");
        Long shopId = shopIdOf(seller.getId());
        audit(adminToken, shopId, ShopStatus.ACTIVE, null);

        JsonNode updated = putJson("/seller/shop/mine", sellerToken, Map.of(
                "name", "改名后", "description", "顺便改了简介", "contactPhone", "13900001111"));
        assertThat(updated.path("code").asInt()).isEqualTo(200);

        ShopEntity shop = shopMapper.selectById(shopId);
        assertThat(shop.getName()).isEqualTo("改名后");
        // 改个简介不该触发重新审核、也不该把店铺打回待审核
        assertThat(shop.getStatus()).isEqualTo(ShopStatus.ACTIVE);
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

    private void linkRole(Long userId, String roleCode) {
        RoleEntity role = roleMapper.selectOne(
                Wrappers.<RoleEntity>lambdaQuery().eq(RoleEntity::getCode, roleCode));
        assertThat(role).as("角色 %s 不存在，请先执行 07_mall_shop.sql", roleCode).isNotNull();

        UserRoleEntity relation = new UserRoleEntity();
        relation.setUserId(userId);
        relation.setRoleId(role.getId());
        userRoleMapper.insert(relation);
        permissionService.refreshPermissions(userId);
    }

    /**
     * 审核通过一个卖家，返回其用户 ID。
     *
     * <p>注意：这里刻意<b>先登录再审核</b>并缓存令牌 —— 后面所有用例都用这枚
     * 「审核之前签发的旧令牌」去调卖家接口。这是对"权限走 Redis 而非 JWT"的持续验证：
     * 只要哪次改动把权限判定挪回了令牌里，这些用例会立刻失效。</p>
     */
    private Long approvedSeller(String adminToken, String prefix, String shopName) throws Exception {
        UserEntity seller = createUser(prefix);
        String token = login(seller.getUsername());
        cachedTokens.put(seller.getId(), token);

        apply(token, shopName);
        audit(adminToken, shopIdOf(seller.getId()), ShopStatus.ACTIVE, null);
        return seller.getId();
    }

    /** userId → 已签发的令牌，避免同一个用户反复登录 */
    private final Map<Long, String> cachedTokens = new HashMap<>();

    private String tokenOfUser(Long userId) {
        return cachedTokens.get(userId);
    }

    private Long shopIdOf(Long userId) {
        ShopEntity shop = shopMapper.selectOne(
                Wrappers.<ShopEntity>lambdaQuery().eq(ShopEntity::getUserId, userId));
        assertThat(shop).as("用户 %s 应当已有店铺", userId).isNotNull();
        return shop.getId();
    }

    private JsonNode apply(String token, String shopName) throws Exception {
        return postJson("/seller/shop/apply", token, Map.of(
                "name", shopName, "description", "自动化测试店铺", "contactPhone", "13800000000"));
    }

    private JsonNode audit(String adminToken, Long shopId, int status, String rejectReason) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        if (rejectReason != null) {
            body.put("rejectReason", rejectReason);
        }
        return putJson("/admin/shop/" + shopId + "/audit", adminToken, body);
    }

    private JsonNode createProduct(String token, String name) throws Exception {
        return postJson("/seller/product", token, productJson(name));
    }

    private Map<String, Object> productJson(String name) {
        return Map.of(
                "name", name,
                "price", new BigDecimal("19.90"),
                "stock", 10);
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

    private JsonNode putJson(String url, String token, Object body) throws Exception {
        var request = put(url).contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : objectMapper.writeValueAsString(body));
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

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
