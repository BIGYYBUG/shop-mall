package com.mall.service.rbac;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.api.vo.PermissionVO;
import com.mall.convert.rbac.PermissionVoFactory;
import com.mall.entity.PermissionEntity;
import com.mall.mapper.PermissionMapper;
import com.mall.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 权限服务实现：数据库 + Redis 两级读取。
 *
 * <h3>缓存设计（三件事想清楚就够了）</h3>
 *
 * <p><b>① 为什么用 Redis 而不是缓存进 JWT？</b>
 * 令牌签发后到过期前无法撤销。如果把权限塞进令牌，管理员刚收回某人的权限，
 * 他手里的旧令牌还能用到过期 —— 这在安全上不可接受。Redis 支持主动删除，
 * 收回权限时把 key 删掉即刻生效。</p>
 *
 * <p><b>② 缓存什么结构？</b>
 * 用 Set 而不是 String。因为校验权限是个高频「是否包含」判断，
 * Set 的 {@code contains} 是 O(1)，而存成逗号拼接的字符串就得每次都 split + 遍历。
 * Redis 的 {@code SISMEMBER} 同样是 O(1)。</p>
 *
 * <p><b>③ 怎么防缓存穿透？</b>
 * 一个用户如果压根没有任何权限，回源查到的就是空集合。而空集合在 Redis 里
 * 存不住（SADD 不添加任何成员时 key 会被自动删除）。结果是：每次请求都读不到
 * 缓存 → 每次都回源查数据库，缓存等于失效。
 * 解法是写入一个哨兵值 {@link #EMPTY_FLAG} —— 用成员非空骗过 Redis 保住 key，
 * 读出时再把它摘掉。哨兵值必须不可能与真实权限编码冲突。</p>
 *
 * <p><b>已知局限</b>：{@code SADD} 与 {@code EXPIRE} 是两条命令，中间若进程崩溃
 * 会留下一个永不过期的 key。生产环境应改用 Lua 脚本保证原子性，或使用
 * Redis 6+ 的 {@code SET} 带 {@code EX} 语义的替代写法。本机 Redis 3.0 不支持，
 * 故按两步实现并在此标注。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl implements PermissionService {

    /** 权限缓存 key 前缀 */
    private static final String PERM_KEY_PREFIX = "mall:perm:user:";

    /** 角色缓存 key 前缀 */
    private static final String ROLE_KEY_PREFIX = "mall:role:user:";

    /** 空集合哨兵值：不含冒号，与「资源:动作」的权限编码格式天然不冲突 */
    private static final String EMPTY_FLAG = "__EMPTY__";

    /** 缓存有效期：30 分钟。太长则收权不及时，太短则退化成每次都查库 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final PermissionMapper permissionMapper;
    private final RoleMapper roleMapper;
    private final StringRedisTemplate redisTemplate;
    /** VO 装配统一走工厂 */
    private final PermissionVoFactory permissionVoFactory;

    @Override
    public Set<String> getPermissions(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        return readThrough(PERM_KEY_PREFIX + userId,
                () -> permissionMapper.selectCodesByUserId(userId));
    }

    @Override
    public Set<String> getRoleCodes(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        return readThrough(ROLE_KEY_PREFIX + userId,
                () -> roleMapper.selectCodesByUserId(userId));
    }

    @Override
    public void refreshPermissions(Long userId) {
        if (userId == null) {
            return;
        }
        clearPermissions(userId);
        // 清完立刻回源一次，避免下一个请求同时去查库（缓存击穿）
        getPermissions(userId);
        getRoleCodes(userId);
        log.debug("已刷新用户权限缓存：userId={}", userId);
    }

    @Override
    public void refreshByRoleId(Long roleId) {
        if (roleId == null) {
            return;
        }
        List<Long> userIds = roleMapper.selectUserIdsByRoleId(roleId);
        if (userIds == null || userIds.isEmpty()) {
            log.debug("角色下暂无用户，无需刷新缓存：roleId={}", roleId);
            return;
        }
        // 逐个刷新而不是 clearPermissions —— 刷新会立刻回源重建，
        // 避免紧接着的请求集中打到数据库（缓存击穿）。
        userIds.forEach(this::refreshPermissions);
        log.info("角色权限已变更，已刷新 {} 个用户的权限缓存：roleId={}", userIds.size(), roleId);
    }

    @Override
    public List<PermissionVO> listPermissions() {
        // 走 sort 再走 id：sort 是分组顺序（10 user / 20 role / 40 product ...），
        // id 只作兜底，保证两个 sort 相同的权限顺序稳定、不会每次查询都跳来跳去。
        List<PermissionEntity> rows = permissionMapper.selectList(
                Wrappers.<PermissionEntity>lambdaQuery()
                        .orderByAsc(PermissionEntity::getSort)
                        .orderByAsc(PermissionEntity::getId));
        return permissionVoFactory.createList(rows);
    }

    @Override
    public void clearPermissions(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.delete(List.of(PERM_KEY_PREFIX + userId, ROLE_KEY_PREFIX + userId));
        log.debug("已清除用户权限缓存：userId={}", userId);
    }

    @Override
    public boolean hasPermission(Long userId, String permissionCode) {
        if (permissionCode == null) {
            return false;
        }
        return getPermissions(userId).contains(permissionCode);
    }

    @Override
    public boolean hasAnyPermission(Long userId, Set<String> permissionCodes) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return false;
        }
        Set<String> owned = getPermissions(userId);
        return permissionCodes.stream().anyMatch(owned::contains);
    }

    /**
     * 缓存读取模板：先读 Redis，未命中再回源，回源后写回缓存。
     *
     * <p>这个模式叫 Cache-Aside（旁路缓存），是最常用的缓存策略：
     * 应用自己管缓存，读时先查缓存、写时删缓存。</p>
     *
     * @param key    缓存键
     * @param loader 回源逻辑（查数据库）
     * @return 权限/角色编码集合，永不为 null
     */
    private Set<String> readThrough(String key, Supplier<List<String>> loader) {
        Set<String> cached = redisTemplate.opsForSet().members(key);
        if (cached != null && !cached.isEmpty()) {
            // 命中缓存：摘掉哨兵值。若摘完为空，说明确实是「无权限」，直接返回空集合
            cached.remove(EMPTY_FLAG);
            return cached;
        }

        List<String> rows = loader.get();
        Set<String> fresh = rows == null
                ? new LinkedHashSet<>()
                : rows.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        writeCache(key, fresh);
        return fresh;
    }

    /**
     * 写缓存（含哨兵逻辑）
     */
    private void writeCache(String key, Set<String> values) {
        if (values.isEmpty()) {
            // 空集合必须靠哨兵值占位，否则 key 存不下来，缓存形同虚设
            redisTemplate.opsForSet().add(key, EMPTY_FLAG);
        } else {
            redisTemplate.opsForSet().add(key, values.toArray(new String[0]));
        }
        redisTemplate.expire(key, CACHE_TTL);
    }
}
