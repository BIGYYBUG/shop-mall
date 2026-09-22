package com.mall;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.common.annotation.RequiresPermission;
import com.mall.common.util.JwtUtil;
import com.mall.controller.AdminPermissionController;
import com.mall.controller.AdminProductController;
import com.mall.controller.AdminRoleController;
import com.mall.controller.AdminShopController;
import com.mall.controller.AdminUserController;
import com.mall.controller.FileController;
import com.mall.controller.SellerProductController;
import com.mall.controller.SellerShopController;
import com.mall.entity.PermissionEntity;
import com.mall.entity.RoleEntity;
import com.mall.entity.UserEntity;
import com.mall.entity.UserRoleEntity;
import com.mall.mapper.PermissionMapper;
import com.mall.mapper.RoleMapper;
import com.mall.mapper.UserMapper;
import com.mall.mapper.UserRoleMapper;
import com.mall.service.rbac.PermissionService;
import io.jsonwebtoken.Claims;
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
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC + OAuth2 + 用户管理 集成测试。
 *
 * <h3>为什么用 MockMvc 而不是起真服务再发 HTTP 请求？</h3>
 * <ul>
 *   <li><b>快</b>：不需要真正监听端口，也不用手动启停进程</li>
 *   <li><b>可回滚</b>：类上的 {@code @Transactional} 让每个测试方法结束时
 *       自动回滚数据库改动，测试之间互不影响，也不会把生产数据搞脏</li>
 *   <li><b>能覆盖到切面</b>：MockMvc 走的是完整的 Spring MVC 链路，
 *       拦截器、AOP 切面、异常处理器全都会真实执行 —— 测的是真链路，不是假象</li>
 * </ul>
 *
 * <h3>前置条件</h3>
 * 本机 MySQL（mall 库，需已执行 03 / 06 / 07 三个脚本）与 Redis 需处于运行状态。
 *
 * <h3>运行方式</h3>
 * <pre>docs/mvnw.sh test -Dtest=RbacIntegrationTest</pre>
 *
 * <h3>覆盖范围</h3>
 * <ol>
 *   <li>认证：无令牌 / 伪造令牌</li>
 *   <li>授权：权限拦截、缓存命中与 TTL、哨兵防穿透</li>
 *   <li>授权变更：授予 / 收回角色与权限后，旧令牌立即生效</li>
 *   <li>用户管理：改 / 禁 / 删 / 重置密码</li>
 *   <li>角色管理：增删改查、内置角色保护、占用检查、编码复用</li>
 *   <li>权限字典：排序、分配、自锁保护</li>
 *   <li>防漏配：代码里的 @RequiresPermission 必须都在字典里存在</li>
 *   <li>第三方登录：未配置时的降级行为</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RbacIntegrationTest {

    private static final String RAW_PASSWORD = "123456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本次测试创建的用户 ID，用于结束后清理 Redis 缓存 */
    private final List<Long> createdUserIds = new ArrayList<>();

    /**
     * 清理本次测试在 Redis 里留下的权限缓存。
     *
     * <p><b>为什么不能只清 createdUserIds</b>：数据库改动由 {@code @Transactional}
     * 回滚，但 Redis 不在事务管辖范围内。更麻烦的是「给角色分配权限」这类操作
     * 会刷新该角色下<b>所有人</b>的缓存 —— 包括测试没有创建、但真实存在于库里的
     * 账号（如 marin）。这些缓存在事务回滚后仍保留着"事务内看到的那份权限集合"，
     * 会污染后续用例，且现象极难定位（表现为某个不相干的用例偶发 403）。</p>
     *
     * <p>因此这里按 key 前缀整段清除。用 {@code KEYS} 命令在测试里可以接受
     * （键数量极少、且只作用于本项目的命名空间），<b>但生产代码绝不能这么写</b> ——
     * {@code KEYS} 是 O(N) 全库扫描，会阻塞 Redis 主线程。</p>
     */
    @AfterEach
    void cleanRedisCache() {
        deleteByPattern("mall:perm:user:*");
        deleteByPattern("mall:role:user:*");
        createdUserIds.clear();
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // ==================================================================
    // 认证：没有令牌 / 用假令牌
    // ==================================================================

    @Test
    @DisplayName("未携带令牌访问管理接口 → 401")
    void noTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/admin/user/page"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("伪造令牌访问管理接口 → 401（验签失败）")
    void forgedTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/admin/user/page")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9.forged.payload"))
                .andExpect(status().isUnauthorized());
    }

    // ==================================================================
    // 登录：令牌内容
    // ==================================================================

    @Test
    @DisplayName("登录成功：令牌携带 userId 与 roles，且响应体不含密码")
    void loginShouldReturnTokenWithRolesAndNoPassword() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");

        JsonNode body = loginAndGetBody(admin.getUsername(), RAW_PASSWORD);

        assertThat(body.path("code").asInt()).isEqualTo(200);

        // 令牌 payload 里应有 roles
        String token = body.path("data").path("token").asText();
        Claims claims = jwtUtil.parseToken(token);
        assertThat(claims).isNotNull();
        assertThat(jwtUtil.getUserId(claims)).isEqualTo(admin.getId());
        assertThat(jwtUtil.extractRoles(claims)).containsExactly("ADMIN");

        // 令牌里绝不能出现密码字段
        assertThat(token).doesNotContain(RAW_PASSWORD);

        // 响应体里也不能出现 password
        JsonNode userInfo = body.path("data").path("userInfo");
        assertThat(userInfo.has("password")).isFalse();
        assertThat(userInfo.has("deleted")).isFalse();
        assertThat(userInfo.path("roles").get(0).asText()).isEqualTo("ADMIN");
    }

    // ==================================================================
    // 授权：权限判定
    // ==================================================================

    @Test
    @DisplayName("ADMIN 用户可访问用户分页列表 → 200")
    void adminCanAccessUserPage() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String token = login(admin.getUsername(), RAW_PASSWORD);

        JsonNode body = getJson("/admin/user/page?pageNum=1&pageSize=5", token);
        assertThat(body.path("code").asInt()).isEqualTo(200);
        assertThat(body.path("data").has("total")).isTrue();
        assertThat(body.path("data").path("records")).isNotNull();
    }

    @Test
    @DisplayName("普通用户访问用户分页列表 → 403（核心：权限拦截生效）")
    void normalUserCannotAccessUserPage() throws Exception {
        UserEntity normal = createUser("t_user_", "USER");
        String token = login(normal.getUsername(), RAW_PASSWORD);

        JsonNode body = getJson("/admin/user/page", token);
        assertThat(body.path("code").asInt()).isEqualTo(403);
        assertThat(body.path("message").asText()).contains("权限不足").contains("user:list");
    }

    @Test
    @DisplayName("普通用户查别人详情 → 403；查自己走 /user/info → 200")
    void normalUserCanOnlyReadOwnProfile() throws Exception {
        UserEntity normal = createUser("t_user_", "USER");
        UserEntity other = createUser("t_other_", "USER");
        String token = login(normal.getUsername(), RAW_PASSWORD);

        // /admin/user/{id} 是管理端能力，普通买家持有它等于能遍历全站用户
        JsonNode forbidden = getJson("/admin/user/" + other.getId(), token);
        assertThat(forbidden.path("code").asInt()).isEqualTo(403);

        // 查自己的正确入口：只认令牌里的 userId，不接受路径参数
        JsonNode mine = getJson("/user/info", token);
        assertThat(mine.path("code").asInt()).isEqualTo(200);
        assertThat(mine.path("data").path("username").asText()).isEqualTo(normal.getUsername());
    }

    @Test
    @DisplayName("完全无角色的用户：权限为空，且 Redis 用哨兵值占位（防缓存穿透）")
    void userWithNoRoleCachesEmptySentinel() {
        UserEntity nobody = createUser("t_none_", null);

        assertThat(permissionService.getPermissions(nobody.getId())).isEmpty();

        // 直接读原始缓存内容，验证确实写入了 __EMPTY__ 哨兵
        Set<String> raw = redisTemplate.opsForSet().members("mall:perm:user:" + nobody.getId());
        assertThat(raw).containsExactly("__EMPTY__");
    }

    @Test
    @DisplayName("权限缓存有 TTL，不会永久驻留")
    void permissionCacheHasTtl() {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        permissionService.getPermissions(admin.getId());

        Long ttl = redisTemplate.getExpire("mall:perm:user:" + admin.getId());
        assertThat(ttl).isNotNull().isGreaterThan(0);
    }

    // ==================================================================
    // 授权变更：缓存必须实时失效
    // ==================================================================

    @Test
    @DisplayName("授予角色后，同一枚旧令牌立刻获得新权限（缓存实时刷新）")
    void grantRoleTakesEffectImmediately() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        UserEntity normal = createUser("t_user_", "USER");

        String adminToken = login(admin.getUsername(), RAW_PASSWORD);
        String normalToken = login(normal.getUsername(), RAW_PASSWORD);

        // 授权前：被拦住
        assertThat(getJson("/admin/user/page", normalToken).path("code").asInt()).isEqualTo(403);

        // 管理员把普通用户的角色覆盖为 ADMIN
        Long adminRoleId = requireRoleId("ADMIN");
        JsonNode assignResult = putJson("/admin/user/" + normal.getId() + "/roles",
                adminToken, Map.of("roleIds", List.of(adminRoleId)));
        assertThat(assignResult.path("code").asInt()).isEqualTo(200);

        // 授权后：不加任何等待，用同一枚旧令牌再请求，应立即放行。
        // 这一条就是在验证「角色变更 → Redis 权限缓存刷新」没有被漏掉。
        assertThat(getJson("/admin/user/page", normalToken).path("code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("收回角色后，同一枚旧令牌立刻失去权限（收权即刻生效）")
    void revokeRoleTakesEffectImmediately() throws Exception {
        // 由「另一个管理员」执行收回 —— 新加了自锁保护，管理员不能摘掉自己的 ADMIN 角色
        UserEntity operator = createUser("t_admin_", "ADMIN");
        UserEntity victim = createUser("t_admin_", "ADMIN");

        String operatorToken = login(operator.getUsername(), RAW_PASSWORD);
        String victimToken = login(victim.getUsername(), RAW_PASSWORD);

        // 收回前：受害者的令牌是能进后台的
        assertThat(getJson("/admin/user/page", victimToken).path("code").asInt()).isEqualTo(200);

        // 覆盖为空数组 = 收回全部角色
        JsonNode revoked = putJson("/admin/user/" + victim.getId() + "/roles",
                operatorToken, Map.of("roleIds", List.of()));
        assertThat(revoked.path("code").asInt()).isEqualTo(200);

        // 立即生效：不等待缓存 TTL
        assertThat(getJson("/admin/user/page", victimToken).path("code").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("管理员不能摘掉自己的 ADMIN 角色 → 400（防止把自己锁在门外）")
    void cannotRemoveOwnAdminRole() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String token = login(admin.getUsername(), RAW_PASSWORD);

        JsonNode body = putJson("/admin/user/" + admin.getId() + "/roles",
                token, Map.of("roleIds", List.of()));

        assertThat(body.path("code").asInt()).isEqualTo(400);
        assertThat(body.path("message").asText()).contains("不能移除自己的 ADMIN");

        // 角色确实还在 —— 拒绝发生在写入之前
        assertThat(roleMapper.selectCodesByUserId(admin.getId())).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("管理员可以摘掉自己的非 ADMIN 角色（保护只针对 ADMIN）")
    void canRemoveOwnNonAdminRole() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        // 额外追加一个 USER 角色
        linkRole(admin.getId(), "USER");
        String token = login(admin.getUsername(), RAW_PASSWORD);

        JsonNode body = putJson("/admin/user/" + admin.getId() + "/roles",
                token, Map.of("roleIds", List.of(requireRoleId("ADMIN"))));

        assertThat(body.path("code").asInt()).isEqualTo(200);
        assertThat(roleMapper.selectCodesByUserId(admin.getId())).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("分配不存在的角色 ID → 400，且不影响已有角色")
    void assignInvalidRoleShouldFail() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String adminToken = login(admin.getUsername(), RAW_PASSWORD);

        JsonNode body = putJson("/admin/user/" + admin.getId() + "/roles",
                adminToken, Map.of("roleIds", List.of(99999999L)));
        assertThat(body.path("code").asInt()).isEqualTo(400);
    }

    // ==================================================================
    // 用户管理：改 / 禁 / 删
    // ==================================================================

    @Test
    @DisplayName("管理员不能禁用自己 → 400")
    void adminCannotDisableSelf() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String adminToken = login(admin.getUsername(), RAW_PASSWORD);

        JsonNode body = putJson("/admin/user/" + admin.getId() + "/status?status=0", adminToken, null);
        assertThat(body.path("code").asInt()).isEqualTo(400);
        assertThat(body.path("message").asText()).contains("不能禁用当前登录");
    }

    @Test
    @DisplayName("禁用用户后，该账号无法登录 → 403")
    void disabledUserCannotLogin() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        UserEntity normal = createUser("t_user_", "USER");
        String adminToken = login(admin.getUsername(), RAW_PASSWORD);

        putJson("/admin/user/" + normal.getId() + "/status?status=0", adminToken, null);

        JsonNode loginBody = loginAndGetBody(normal.getUsername(), RAW_PASSWORD);
        assertThat(loginBody.path("code").asInt()).isEqualTo(403);
        assertThat(loginBody.path("message").asText()).contains("禁用");
    }

    @Test
    @DisplayName("重置密码后，旧密码失效、新密码可登录")
    void resetPasswordShouldWork() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        UserEntity normal = createUser("t_user_", "USER");
        String adminToken = login(admin.getUsername(), RAW_PASSWORD);

        JsonNode reset = putJson("/admin/user/" + normal.getId() + "/password",
                adminToken, Map.of("newPassword", "newpass888"));
        assertThat(reset.path("code").asInt()).isEqualTo(200);

        assertThat(loginAndGetBody(normal.getUsername(), RAW_PASSWORD).path("code").asInt()).isEqualTo(400);
        assertThat(loginAndGetBody(normal.getUsername(), "newpass888").path("code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("修改用户信息：空串被归一化为 null，不写入脏数据")
    void updateUserShouldNormalizeBlankToNull() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        UserEntity normal = createUser("t_user_", "USER");
        String adminToken = login(admin.getUsername(), RAW_PASSWORD);

        JsonNode body = putJson("/admin/user/" + normal.getId(), adminToken, Map.of(
                "nickname", "改过的昵称",
                "email", "",
                "phone", "",
                "avatar", ""
        ));
        assertThat(body.path("code").asInt()).isEqualTo(200);

        UserEntity updated = userMapper.selectById(normal.getId());
        assertThat(updated.getNickname()).isEqualTo("改过的昵称");
        // 空串必须落成 null，否则后期做数据清洗会非常痛苦
        assertThat(updated.getEmail()).isNull();
        assertThat(updated.getPhone()).isNull();
        assertThat(updated.getAvatar()).isNull();
    }

    @Test
    @DisplayName("删除用户是逻辑删除：接口查不到，但数据行物理上还在")
    void deleteUserShouldBeLogical() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        UserEntity normal = createUser("t_user_", "USER");
        String adminToken = login(admin.getUsername(), RAW_PASSWORD);

        mockMvc.perform(delete("/admin/user/" + normal.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        // ① 业务上查不到了
        assertThat(getJson("/admin/user/" + normal.getId(), adminToken).path("code").asInt()).isEqualTo(404);
        assertThat(userMapper.selectById(normal.getId())).isNull();

        // ② 但物理行仍在，deleted 被写成「本行自己的 id」—— 用原生 SQL 绕过逻辑删除过滤
        Long deletedFlag = jdbcTemplate.queryForObject(
                "SELECT deleted FROM mall_user WHERE id = ?", Long.class, normal.getId());
        assertThat(deletedFlag)
                .as("删除值必须是本行 id，恒为 1 会让同名账号二次注销时撞 uk_username")
                .isEqualTo(normal.getId());
    }

    @Test
    @DisplayName("同名账号可以「注销 → 重新注册 → 再注销」，不撞唯一索引")
    void usernameCanBeReusedAfterDeletion() {
        // 这一条专门守 uk_username(username, deleted) 的语义。
        // 若 deleted 恒为 1，第二轮 deleteById 会把新行也写成 1，
        // 与第一轮已删除的行形成 (username, 1) 冲突，直接抛 Duplicate entry。
        String username = "t_reuse_" + UUID.randomUUID().toString().substring(0, 8);

        for (int round = 1; round <= 3; round++) {
            UserEntity user = new UserEntity();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(RAW_PASSWORD));
            user.setStatus(1);
            userMapper.insert(user);
            createdUserIds.add(user.getId());

            userMapper.deleteById(user.getId());

            Long deletedFlag = jdbcTemplate.queryForObject(
                    "SELECT deleted FROM mall_user WHERE id = ?", Long.class, user.getId());
            assertThat(deletedFlag)
                    .as("第 %d 轮注销后 deleted 应等于本行 id", round)
                    .isEqualTo(user.getId());
        }

        // 三轮之后，仍未删除的行数为 0（全部已注销），且历史行都还在
        Integer alive = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mall_user WHERE username = ? AND deleted = 0",
                Integer.class, username);
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mall_user WHERE username = ?",
                Integer.class, username);
        assertThat(alive).isZero();
        assertThat(total).isEqualTo(3);
    }

    @Test
    @DisplayName("注册接口自动绑定默认 USER 角色，且该角色不含任何管理端权限")
    void registerShouldBindDefaultRole() throws Exception {
        String username = "t_reg_" + UUID.randomUUID().toString().substring(0, 8);

        MvcResult result = mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", RAW_PASSWORD,
                                "nickname", "注册测试"
                        ))))
                .andReturn();

        JsonNode registerBody = readJson(result);
        assertThat(registerBody.path("code").asInt()).isEqualTo(200);
        Long newUserId = registerBody.path("data").asLong();
        createdUserIds.add(newUserId);

        // 角色确实落库了
        List<String> roleCodes = roleMapper.selectCodesByUserId(newUserId);
        assertThat(roleCodes).containsExactly("USER");

        // USER 角色名下不挂任何权限码：新用户的权限集合必须为空，
        // 且缓存里应当是 __EMPTY__ 哨兵（读 /user/info 不需要权限码，依然能通）
        assertThat(permissionService.getPermissions(newUserId)).isEmpty();

        JsonNode info = getJson("/user/info", login(username, RAW_PASSWORD));
        assertThat(info.path("code").asInt()).isEqualTo(200);
        assertThat(info.path("data").path("username").asText()).isEqualTo(username);

        // 反证：真正的管理端接口必须被拦住
        JsonNode forbidden = getJson("/admin/user/page", login(username, RAW_PASSWORD));
        assertThat(forbidden.path("code").asInt()).isEqualTo(403);
    }

    // ==================================================================
    // OAuth2 / 微信登录
    // ==================================================================

    @Test
    @DisplayName("未配置 AppID 时，可用渠道列表为空，且请求授权地址返回明确错误")
    void wechatNotConfiguredShouldFailGracefully() throws Exception {
        JsonNode sources = getJson("/auth/sources", null);
        assertThat(sources.path("code").asInt()).isEqualTo(200);
        assertThat(sources.path("data").isArray()).isTrue();
        // 当前 application.yml 中 app-id 为空，因此不应出现 wechat
        assertThat(sources.path("data").toString()).doesNotContain("wechat");

        JsonNode urlBody = getJson("/auth/wechat/authorize-url", null);
        assertThat(urlBody.path("code").asInt()).isEqualTo(500);
        assertThat(urlBody.path("message").asText()).contains("mall.wechat.app-id");
    }

    @Test
    @DisplayName("微信登录传空 code → 400 参数校验失败")
    void wechatLoginWithBlankCodeShouldFail() throws Exception {
        JsonNode body = postJson("/auth/wechat/login", null, Map.of("code", ""));
        assertThat(body.path("code").asInt()).isEqualTo(400);
    }

    @Test
    @DisplayName("微信登录：合法调用会被路由到 provider 的配置校验分支")
    void wechatLoginRoutesToProvider() throws Exception {
        JsonNode body = postJson("/auth/wechat/login", null, Map.of("code", "dummy-code"));
        // 未配置 AppID，Provider 第一步就抛 500，说明路由正确、只是缺配置
        assertThat(body.path("code").asInt()).isEqualTo(500);
        assertThat(body.path("message").asText()).contains("mall.wechat");
    }

    // ==================================================================
    // 角色自身的增删改 —— 本轮补齐的管理闭环
    // ==================================================================

    @Test
    @DisplayName("新增角色：创建成功，详情里 builtIn 为 0、权限列表为空数组")
    void createRoleShouldSucceed() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String token = login(admin.getUsername(), RAW_PASSWORD);
        String code = "T_ROLE_" + rand();

        JsonNode created = postJson("/admin/role", token, Map.of(
                "code", code, "name", "测试角色", "description", "自动化测试", "status", 1, "sort", 99));
        assertThat(created.path("code").asInt()).isEqualTo(200);
        Long roleId = created.path("data").asLong();

        JsonNode detail = getJson("/admin/role/" + roleId, token);
        assertThat(detail.path("code").asInt()).isEqualTo(200);
        assertThat(detail.path("data").path("code").asText()).isEqualTo(code);
        // 界面创建的一律是自定义角色 —— builtIn 不接受外部传入，
        // 否则调用方能自己造一个"不可删除"的角色
        assertThat(detail.path("data").path("builtIn").asInt()).isZero();
        // 新角色没有任何权限，两个字段应是空数组而不是 null
        assertThat(detail.path("data").path("permissionIds").size()).isZero();
        assertThat(detail.path("data").path("permissions").size()).isZero();
    }

    @Test
    @DisplayName("新增角色：编码重复 → 400")
    void createRoleWithDuplicateCodeShouldFail() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String token = login(admin.getUsername(), RAW_PASSWORD);
        String code = "T_DUP_" + rand();

        assertThat(createRoleViaApi(token, code).path("code").asInt()).isEqualTo(200);

        JsonNode again = postJson("/admin/role", token, Map.of(
                "code", code, "name", "重复角色", "status", 1));
        assertThat(again.path("code").asInt()).isEqualTo(400);
        assertThat(again.path("message").asText()).contains("已存在");
    }

    @Test
    @DisplayName("新增角色：编码不符合大写规范 → 400（校验注解生效）")
    void createRoleWithInvalidCodeShouldFail() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String token = login(admin.getUsername(), RAW_PASSWORD);

        // 小写、含短横线的编码都该被 @Pattern 拦住
        for (String bad : List.of("lowercase", "has-dash", "1starts_with_digit")) {
            JsonNode body = postJson("/admin/role", token, Map.of(
                    "code", bad, "name", "非法编码", "status", 1));
            assertThat(body.path("code").asInt())
                    .as("编码 %s 应被拒绝", bad)
                    .isEqualTo(400);
        }
    }

    @Test
    @DisplayName("修改角色：能改名称与描述，但改不动 code")
    void updateRoleShouldNotChangeCode() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String token = login(admin.getUsername(), RAW_PASSWORD);
        String code = "T_UPD_" + rand();
        Long roleId = createRoleViaApi(token, code).path("data").asLong();

        JsonNode updated = putJson("/admin/role/" + roleId, token, Map.of(
                "name", "改过的名字", "description", "改过的描述", "status", 1, "sort", 5));
        assertThat(updated.path("code").asInt()).isEqualTo(200);

        JsonNode detail = getJson("/admin/role/" + roleId, token);
        assertThat(detail.path("data").path("name").asText()).isEqualTo("改过的名字");
        // 编码是令牌里被引用的标识，创建后不可变。
        // RoleUpdateDTO 里根本没有 code 字段，所以这里必然保持原值
        assertThat(detail.path("data").path("code").asText()).isEqualTo(code);
    }

    @Test
    @DisplayName("内置角色不允许删除 → 400")
    void builtInRoleCannotBeDeleted() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String token = login(admin.getUsername(), RAW_PASSWORD);
        Long adminRoleId = requireRoleId("ADMIN");

        JsonNode body = deleteJson("/admin/role/" + adminRoleId, token);
        assertThat(body.path("code").asInt()).isEqualTo(400);
        assertThat(body.path("message").asText()).contains("内置角色");

        // 角色确实还在 —— 拒绝发生在写入之前
        assertThat(roleMapper.selectById(adminRoleId)).isNotNull();
    }

    @Test
    @DisplayName("内置角色不允许停用 → 400（停用 ADMIN 会让所有管理员瞬间失去权限）")
    void builtInRoleCannotBeDisabled() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String token = login(admin.getUsername(), RAW_PASSWORD);

        JsonNode body = putJson("/admin/role/" + requireRoleId("ADMIN"), token, Map.of(
                "name", "超级管理员", "description", "改描述是可以的", "status", 0));

        assertThat(body.path("code").asInt()).isEqualTo(400);
        assertThat(body.path("message").asText()).contains("不允许停用");
    }

    @Test
    @DisplayName("仍被用户持有的角色不允许删除 → 400")
    void roleInUseCannotBeDeleted() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String token = login(admin.getUsername(), RAW_PASSWORD);
        String code = "T_USE_" + rand();
        Long roleId = createRoleViaApi(token, code).path("data").asLong();

        // 造一个持有该角色的用户
        UserEntity holder = createUser("t_holder_", null);
        linkRole(holder.getId(), code);

        JsonNode body = deleteJson("/admin/role/" + roleId, token);
        assertThat(body.path("code").asInt()).isEqualTo(400);
        assertThat(body.path("message").asText()).contains("仍被").contains("持有");
    }

    @Test
    @DisplayName("删除无人使用的角色后，同一编码可以再次创建（deleted=id 语义在角色表同样生效）")
    void deleteUnusedRoleFreesItsCode() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String token = login(admin.getUsername(), RAW_PASSWORD);
        String code = "T_RECYCLE_" + rand();

        Long firstId = createRoleViaApi(token, code).path("data").asLong();
        assertThat(deleteJson("/admin/role/" + firstId, token).path("code").asInt()).isEqualTo(200);
        assertThat(roleMapper.selectById(firstId)).isNull();

        // 编码可以复用 —— 若 deleted 恒为 1，这里会撞 uk_code(code, deleted)
        JsonNode second = createRoleViaApi(token, code);
        assertThat(second.path("code").asInt()).isEqualTo(200);
        assertThat(second.path("data").asLong()).isNotEqualTo(firstId);
    }

    // ==================================================================
    // 权限字典与「角色 → 权限」分配
    // ==================================================================

    @Test
    @DisplayName("权限字典：按 sort 升序返回，且同一资源的权限聚在一起")
    void permissionDictionaryIsSorted() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String token = login(admin.getUsername(), RAW_PASSWORD);

        JsonNode body = getJson("/admin/permission/list", token);
        assertThat(body.path("code").asInt()).isEqualTo(200);
        assertThat(body.path("data").size()).isGreaterThan(20);

        int previous = Integer.MIN_VALUE;
        for (JsonNode item : body.path("data")) {
            int sort = item.path("sort").asInt();
            assertThat(sort).as("权限字典必须按 sort 升序").isGreaterThanOrEqualTo(previous);
            previous = sort;
        }
    }

    @Test
    @DisplayName("给角色分配权限后，持有该角色的用户「旧令牌」立刻生效")
    void assignPermissionsTakesEffectImmediately() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String adminToken = login(admin.getUsername(), RAW_PASSWORD);

        String code = "T_GRANT_" + rand();
        Long roleId = createRoleViaApi(adminToken, code).path("data").asLong();

        UserEntity holder = createUser("t_holder_", null);
        linkRole(holder.getId(), code);
        permissionService.refreshPermissions(holder.getId());
        String holderToken = login(holder.getUsername(), RAW_PASSWORD);

        // 分配前：该角色还没绑 user:list，被拦住
        assertThat(getJson("/admin/user/page", holderToken).path("code").asInt()).isEqualTo(403);

        JsonNode assigned = putJson("/admin/role/" + roleId + "/permissions", adminToken,
                Map.of("permissionIds", List.of(permissionIdByCode("user:list"))));
        assertThat(assigned.path("code").asInt()).isEqualTo(200);

        // 关键：不等待任何 TTL，同一枚旧令牌立刻放行。
        // 这一条验证的是 refreshByRoleId —— 只刷「操作者自己」是刷不到 holder 的，
        // 而漏掉这一点时接口仍会返回 200（授权成功），只有真调一次才发现没生效。
        assertThat(getJson("/admin/user/page", holderToken).path("code").asInt()).isEqualTo(200);
    }

    @Test
    @DisplayName("收回角色权限后，持有者的「旧令牌」立刻失效")
    void revokePermissionsTakesEffectImmediately() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String adminToken = login(admin.getUsername(), RAW_PASSWORD);

        String code = "T_REVOKE_" + rand();
        Long roleId = createRoleViaApi(adminToken, code).path("data").asLong();
        putJson("/admin/role/" + roleId + "/permissions", adminToken,
                Map.of("permissionIds", List.of(permissionIdByCode("user:list"))));

        UserEntity holder = createUser("t_holder_", null);
        linkRole(holder.getId(), code);
        permissionService.refreshPermissions(holder.getId());
        String holderToken = login(holder.getUsername(), RAW_PASSWORD);

        assertThat(getJson("/admin/user/page", holderToken).path("code").asInt()).isEqualTo(200);

        // 清空该角色的权限
        assertThat(putJson("/admin/role/" + roleId + "/permissions", adminToken,
                Map.of("permissionIds", List.of())).path("code").asInt()).isEqualTo(200);

        // 收权即刻生效 —— 漏刷缓存时这里会错误地返回 200，是典型的安全漏洞
        assertThat(getJson("/admin/user/page", holderToken).path("code").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("给角色分配不存在的权限 ID → 400，且不影响已有绑定")
    void assignInvalidPermissionShouldFail() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String token = login(admin.getUsername(), RAW_PASSWORD);
        Long adminRoleId = requireRoleId("ADMIN");

        JsonNode body = putJson("/admin/role/" + adminRoleId + "/permissions", token,
                Map.of("permissionIds", List.of(99999999L)));
        assertThat(body.path("code").asInt()).isEqualTo(400);

        // ADMIN 的权限一条都没少 —— 校验发生在删除之前
        JsonNode detail = getJson("/admin/role/" + adminRoleId, token);
        assertThat(detail.path("data").path("permissionIds").size()).isGreaterThan(20);
    }

    @Test
    @DisplayName("不能从 ADMIN 移除 role:permission —— 它是撤销授权的唯一入口")
    void cannotRemoveSelfLockPermission() throws Exception {
        UserEntity admin = createUser("t_admin_", "ADMIN");
        String token = login(admin.getUsername(), RAW_PASSWORD);
        Long adminRoleId = requireRoleId("ADMIN");

        // 构造「除 role:permission 之外的全部权限」
        JsonNode dictionary = getJson("/admin/permission/list", token);
        List<Long> withoutSelfLock = new ArrayList<>();
        dictionary.path("data").forEach(item -> {
            if (!"role:permission".equals(item.path("code").asText())) {
                withoutSelfLock.add(item.path("id").asLong());
            }
        });
        assertThat(withoutSelfLock).isNotEmpty();

        JsonNode body = putJson("/admin/role/" + adminRoleId + "/permissions", token,
                Map.of("permissionIds", withoutSelfLock));

        assertThat(body.path("code").asInt()).isEqualTo(400);
        assertThat(body.path("message").asText()).contains("role:permission");

        // 权限没被改动
        assertThat(permissionService.getPermissions(admin.getId())).contains("role:permission");
    }

    // ==================================================================
    // 防漏配检查：这条测试专门用来防"后期维护困难"
    // ==================================================================

    @Test
    @DisplayName("代码里 @RequiresPermission 用到的每个权限码，都必须在权限字典里存在")
    void everyAnnotatedPermissionCodeExistsInDictionary() {
        // 症状回顾：新增一个接口 + 一个权限码注解，却忘了往 mall_permission 插一行。
        // 编译通过、启动正常、接口却永远 403 —— 而且只有真调一次才会发现。
        // 这条测试把「代码与数据的一致性」变成一个可自动执行的断言。
        Set<String> annotated = new TreeSet<>();

        List<Class<?>> controllers = List.of(
                AdminUserController.class, AdminRoleController.class, AdminPermissionController.class,
                AdminProductController.class, AdminShopController.class,
                SellerShopController.class, SellerProductController.class,
                FileController.class);

        for (Class<?> controller : controllers) {
            collect(controller.getAnnotation(RequiresPermission.class), annotated);
            for (Method method : controller.getDeclaredMethods()) {
                collect(method.getAnnotation(RequiresPermission.class), annotated);
            }
        }

        assertThat(annotated)
                .as("应当扫到若干权限码，数量为 0 说明扫描逻辑失效（测试本身没意义了）")
                .hasSizeGreaterThan(10);

        for (String code : annotated) {
            Long count = permissionMapper.selectCount(
                    Wrappers.<PermissionEntity>lambdaQuery().eq(PermissionEntity::getCode, code));
            assertThat(count)
                    .as("权限码 [%s] 在代码中被使用，但 mall_permission 里没有它 —— "
                            + "该接口将永远返回 403。请往 docs/sql/ 里补一条 INSERT", code)
                    .isEqualTo(1L);
        }
    }

    private void collect(RequiresPermission annotation, Set<String> target) {
        if (annotation != null) {
            target.addAll(List.of(annotation.value()));
        }
    }

    // ==================================================================
    // 测试工具
    // ==================================================================

    /**
     * 直接落库创建用户，跳过注册接口 —— 测试要聚焦被测逻辑，减少无关依赖。
     *
     * @param roleCode 为 null 表示不绑定任何角色
     */
    private UserEntity createUser(String prefix, String roleCode) {
        UserEntity user = new UserEntity();
        user.setUsername(prefix + UUID.randomUUID().toString().substring(0, 8));
        user.setPassword(passwordEncoder.encode(RAW_PASSWORD));
        user.setNickname(prefix);
        user.setStatus(1);
        userMapper.insert(user);
        createdUserIds.add(user.getId());

        if (roleCode != null) {
            linkRole(user.getId(), roleCode);
        }
        // 预热缓存，让后续鉴权走真实链路
        permissionService.refreshPermissions(user.getId());
        return user;
    }

    /** 直连数据库建立用户-角色关系（不走接口，避免测试依赖被授予者的权限） */
    private void linkRole(Long userId, String roleCode) {
        UserRoleEntity relation = new UserRoleEntity();
        relation.setUserId(userId);
        relation.setRoleId(requireRoleId(roleCode));
        userRoleMapper.insert(relation);
    }

    /** 经接口创建一个自定义角色，返回完整响应体 */
    private JsonNode createRoleViaApi(String token, String code) throws Exception {
        return postJson("/admin/role", token, Map.of(
                "code", code, "name", "角色 " + code, "description", "自动化测试", "status", 1));
    }

    /** 按权限编码取权限 ID */
    private Long permissionIdByCode(String code) {
        PermissionEntity permission = permissionMapper.selectOne(
                Wrappers.<PermissionEntity>lambdaQuery().eq(PermissionEntity::getCode, code));
        assertThat(permission)
                .as("权限码 %s 不存在，请先执行 docs/sql/06_rbac_complete.sql", code)
                .isNotNull();
        return permission.getId();
    }

    /** 生成一个短随机后缀，避免用例之间撞唯一键 */
    private String rand() {
        return UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private Long requireRoleId(String roleCode) {
        RoleEntity role = roleMapper.selectOne(
                Wrappers.<RoleEntity>lambdaQuery().eq(RoleEntity::getCode, roleCode));
        assertThat(role).as("角色 %s 不存在，请先执行 03_rbac.sql", roleCode).isNotNull();
        return role.getId();
    }

    private String login(String username, String password) throws Exception {
        JsonNode body = loginAndGetBody(username, password);
        assertThat(body.path("code").asInt()).as("登录失败：%s", body.path("message").asText()).isEqualTo(200);
        return body.path("data").path("token").asText();
    }

    private JsonNode loginAndGetBody(String username, String password) throws Exception {
        return postJson("/user/login", null,
                Map.of("username", username, "password", password));
    }

    private JsonNode getJson(String url, String token) throws Exception {
        var request = get(url);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return readJson(mockMvc.perform(request).andReturn());
    }

    private JsonNode putJson(String url, String token, Object body) throws Exception {
        var request = put(url).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        request.content(body == null ? "{}" : objectMapper.writeValueAsString(body));
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
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(content);
    }
}
