package com.mall.service.rbac;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.api.dto.RoleDTO;
import com.mall.api.dto.RoleUpdateDTO;
import com.mall.api.vo.RoleVO;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BusinessException;
import com.mall.convert.rbac.RoleVoFactory;
import com.mall.entity.PermissionEntity;
import com.mall.entity.RoleEntity;
import com.mall.entity.RolePermissionEntity;
import com.mall.entity.UserRoleEntity;
import com.mall.mapper.PermissionMapper;
import com.mall.mapper.RoleMapper;
import com.mall.mapper.RolePermissionMapper;
import com.mall.mapper.UserMapper;
import com.mall.mapper.UserRoleMapper;
import com.mall.mapper.model.UserRoleCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.mall.common.util.TextUtils.trimToNull;

/**
 * 角色服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    /**
     * 「自锁开关」权限编码。
     *
     * <p>{@code role:permission} 是唯一能修改「角色拥有哪些权限」的权限。
     * 一旦某个角色把它摘掉，这个角色就再也无法通过界面给自己加回任何权限 ——
     * 包括这个权限本身。分配权限时必须拦住这种操作，否则一次误点就会
     * 把权限系统的"撤销入口"永久关闭。</p>
     */
    private static final String SELF_LOCK_PERMISSION = "role:permission";

    private static final Integer STATUS_ENABLED = 1;
    private static final Integer STATUS_DISABLED = 0;

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserMapper userMapper;
    private final PermissionService permissionService;
    /** VO 装配统一走工厂 */
    private final RoleVoFactory roleVoFactory;

    // ==================================================================
    // 查询
    // ==================================================================

    @Override
    public List<RoleVO> listRoles() {
        List<RoleEntity> roles = roleMapper.selectList(
                Wrappers.<RoleEntity>lambdaQuery()
                        .eq(RoleEntity::getStatus, STATUS_ENABLED)
                        .orderByAsc(RoleEntity::getSort)
                        .orderByAsc(RoleEntity::getId));
        return roleVoFactory.createList(roles);
    }

    @Override
    public RoleVO getRoleDetail(Long id) {
        RoleEntity role = requireRole(id);

        List<Long> boundIds = rolePermissionMapper.selectList(
                        Wrappers.<RolePermissionEntity>lambdaQuery()
                                .eq(RolePermissionEntity::getRoleId, id))
                .stream()
                .map(RolePermissionEntity::getPermissionId)
                .toList();

        if (boundIds.isEmpty()) {
            return roleVoFactory.createDetail(role, List.of(), List.of());
        }

        // 一次批量取回权限实体（逻辑删除由 MyBatis-Plus 自动追加 deleted = 0），
        // 再在内存里过滤掉已停用的，然后按 sort 排序。
        //
        // 为什么 ID 和编码必须由同一份数据推导出来：
        //   如果 ID 直接取关联表、编码另用一条 SQL 查，两条路径的过滤条件稍有出入
        //   （比如一条过滤了 status、另一条忘了），前端就会出现
        //   「列表里显示 5 条权限，但只勾中了 4 个」这种对不上的现象，
        //   而且保存时会把"看不见的那条"静默删掉。
        //   用同一份 List<PermissionEntity> 同时映射出 id 与 code，从结构上杜绝这种偏差。
        List<PermissionEntity> active = permissionMapper.selectBatchIds(boundIds).stream()
                .filter(p -> STATUS_ENABLED.equals(p.getStatus()))
                .sorted(Comparator
                        .comparing(PermissionEntity::getSort, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(PermissionEntity::getId))
                .toList();

        return roleVoFactory.createDetail(role,
                active.stream().map(PermissionEntity::getId).toList(),
                active.stream().map(PermissionEntity::getCode).toList());
    }

    @Override
    public List<String> listRoleCodesByUserId(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        List<String> codes = roleMapper.selectCodesByUserId(userId);
        return codes == null ? Collections.emptyList() : codes;
    }

    @Override
    public List<Long> listRoleIdsByUserId(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return userRoleMapper.selectList(
                        Wrappers.<UserRoleEntity>lambdaQuery()
                                .eq(UserRoleEntity::getUserId, userId))
                .stream()
                .map(UserRoleEntity::getRoleId)
                .toList();
    }

    @Override
    public Map<Long, List<String>> mapRoleCodesByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<UserRoleCode> rows = roleMapper.selectUserRoleCodes(userIds);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        return rows.stream()
                .filter(row -> row.getUserId() != null && row.getRoleCode() != null)
                .collect(Collectors.groupingBy(
                        UserRoleCode::getUserId,
                        Collectors.mapping(UserRoleCode::getRoleCode, Collectors.toList())));
    }

    // ==================================================================
    // 角色本身的增删改
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRole(RoleDTO dto) {
        // 统一成大写再落库：@Pattern 已经卡住了格式，但客户端的 trim / 大小写
        // 不应该成为"编码重复但查不出来"的理由
        String code = dto.code().trim().toUpperCase(Locale.ROOT);

        if (codeExists(code)) {
            throw new BusinessException(400, "角色编码已存在：" + code);
        }

        RoleEntity entity = new RoleEntity();
        entity.setCode(code);
        entity.setName(dto.name().trim());
        entity.setDescription(trimToNull(dto.description()));
        entity.setStatus(dto.status());
        entity.setSort(dto.sort() == null ? 0 : dto.sort());
        // 界面创建的永远是自定义角色。builtIn 不接受外部传入 —— 否则调用方
        // 可以自己造一个"不可删除"的角色，把删除保护变成用户可控的开关。
        entity.setBuiltIn(0);

        roleMapper.insert(entity);
        log.info("角色已创建：id={}, code={}, name={}", entity.getId(), code, entity.getName());
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRole(Long id, RoleUpdateDTO dto) {
        RoleEntity role = requireRole(id);

        // 内置角色不允许停用。
        // 停用 ADMIN 后，mall_permission 的查询会因 r.status = 1 过滤而返回空集合，
        // 于是所有管理员瞬间失去全部权限 —— 而"把它改回启用"这个动作本身
        // 也需要权限，等于把自己锁在门外，只能改数据库救场。
        if (isBuiltIn(role) && STATUS_DISABLED.equals(dto.status())) {
            throw new BusinessException(400,
                    "内置角色「" + role.getName() + "」不允许停用，否则持有它的账号会立即失去全部权限");
        }

        // 用 lambdaUpdate + set 而不是 updateById：
        // updateById 默认忽略 null 字段，管理员把描述清空时不会被写成 null，
        // 会留下一条"删不掉"的旧描述。
        roleMapper.update(null, Wrappers.<RoleEntity>lambdaUpdate()
                .eq(RoleEntity::getId, id)
                .set(RoleEntity::getName, dto.name().trim())
                .set(RoleEntity::getDescription, trimToNull(dto.description()))
                .set(RoleEntity::getStatus, dto.status())
                .set(RoleEntity::getSort, dto.sort() == null ? 0 : dto.sort()));

        // code 与 builtIn 刻意不在可改范围内：
        //   code —— 已被写进历史 JWT 的 roles 声明，改了会让旧令牌"指向不存在的角色"
        //   builtIn —— 决定了这个角色能否被删除，属于保护标记，不应由编辑接口改动

        // 状态可能从"停用"翻成"启用"，这会一次性改变该角色下所有人的权限集合
        permissionService.refreshByRoleId(id);

        log.info("角色已更新：id={}, code={}, status={}", id, role.getCode(), dto.status());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long id) {
        RoleEntity role = requireRole(id);

        if (isBuiltIn(role)) {
            throw new BusinessException(400,
                    "内置角色「" + role.getName() + "」不允许删除 —— 它是系统运作的前提，删掉后可能再没人能进管理后台");
        }

        // 占用检查：角色还有用户持有时不允许删。
        // 不拦的后果是 mall_user_role 里留下指向不存在角色的孤儿行，
        // 这些用户会"静默降权"（权限少了，却没有任何报错可查）。
        long inUse = roleMapper.countUsersByRoleId(id);
        if (inUse > 0) {
            throw new BusinessException(400,
                    "该角色仍被 " + inUse + " 个用户持有，请先调整这些用户的角色");
        }

        // 关联表没有 deleted 列，两处都是物理删除。
        // 即便占用检查已经通过，user_role 仍可能有残留 —— 逻辑删除掉的用户
        // 不会计入 countUsersByRoleId（那条 SQL join 了 u.deleted = 0），
        // 但他们的关联行还在表里。一并清掉，避免留下孤儿数据。
        rolePermissionMapper.delete(Wrappers.<RolePermissionEntity>lambdaQuery()
                .eq(RolePermissionEntity::getRoleId, id));
        userRoleMapper.delete(Wrappers.<UserRoleEntity>lambdaQuery()
                .eq(UserRoleEntity::getRoleId, id));

        // 角色本体走逻辑删除
        roleMapper.deleteById(id);

        // 兜底刷新：防止并发场景下有人在占用检查与删除之间刚被授予该角色
        permissionService.refreshByRoleId(id);

        log.info("角色已删除：id={}, code={}", id, role.getCode());
    }

    // ==================================================================
    // 授权：角色 ↔ 权限
    // ==================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        requireRole(roleId);

        // 去重 + 去 null。不去重会撞 uk_role_permission 唯一索引
        List<Long> distinctIds = permissionIds == null
                ? List.of()
                : permissionIds.stream().filter(Objects::nonNull).distinct().toList();

        if (!distinctIds.isEmpty()) {
            List<PermissionEntity> permissions = permissionMapper.selectBatchIds(distinctIds);

            // 数量对不上 = 混进了不存在或已删除的权限 ID
            if (permissions.size() != distinctIds.size()) {
                throw new BusinessException(400, "存在无效的权限，请刷新页面后重试");
            }
            // 已停用的权限不允许新分配：分配了也不会生效（查询侧过滤了 status = 1），
            // 只会让界面显示"已勾选"却实际无效，属于给自己埋坑
            if (permissions.stream().anyMatch(p -> !STATUS_ENABLED.equals(p.getStatus()))) {
                throw new BusinessException(400, "存在已停用的权限，无法分配");
            }
        }

        guardSelfLock(roleId, distinctIds);

        // 全量覆盖：先清后建。关联表是物理删除，所以可以放心重复执行
        rolePermissionMapper.delete(Wrappers.<RolePermissionEntity>lambdaQuery()
                .eq(RolePermissionEntity::getRoleId, roleId));

        for (Long permissionId : distinctIds) {
            RolePermissionEntity relation = new RolePermissionEntity();
            relation.setRoleId(roleId);
            relation.setPermissionId(permissionId);
            rolePermissionMapper.insert(relation);
        }

        // 关键一步：本角色下所有人的权限缓存都要作废。
        // 漏掉这行 → 权限已经收回，但这些人手里的令牌在 TTL（30 分钟）内仍然能调通接口。
        permissionService.refreshByRoleId(roleId);

        log.info("角色权限已更新：roleId={}, permissionCount={}", roleId, distinctIds.size());
    }

    // ==================================================================
    // 授权：用户 ↔ 角色
    // ==================================================================

    /**
     * 分配用户角色 —— 本方法里最容易被忽略的不是 SQL，而是最后那行缓存刷新。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        // ① 用户必须存在
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(404, "用户不存在");
        }

        // ② 去重 + 去 null。不去重的话，同一个 roleId 插两次会撞唯一索引 uk_user_role
        List<Long> distinctRoleIds = roleIds == null
                ? List.of()
                : roleIds.stream().filter(Objects::nonNull).distinct().toList();

        // ③ 校验角色合法性。批量查一次，数量对不上说明混进了无效 ID。
        //    不校验的后果：能往关联表里塞入指向不存在角色的脏数据
        if (!distinctRoleIds.isEmpty()) {
            List<RoleEntity> roles = roleMapper.selectBatchIds(distinctRoleIds);
            if (roles.size() != distinctRoleIds.size()) {
                throw new BusinessException(400, "存在无效的角色，请刷新页面后重试");
            }
        }

        // ④ 自锁保护：不允许把「自己的」ADMIN 角色摘掉。
        //    摘掉之后这个账号立刻失去 role:assign，再也无法给自己加回来 ——
        //    与其他管理员无关的话，就是永久性的管理入口丢失。
        //    注意只拦 ADMIN，普通角色随便改，避免把接口限制得过死。
        guardNotRemovingOwnAdmin(userId, distinctRoleIds);

        // ⑤ 全量覆盖：先物理删除旧关系。
        //    关联表没有 deleted 列，这里的 delete 是真 DELETE（见 UserRoleEntity 注释）
        userRoleMapper.delete(Wrappers.<UserRoleEntity>lambdaQuery()
                .eq(UserRoleEntity::getUserId, userId));

        // ⑥ 逐条建立新关系
        for (Long roleId : distinctRoleIds) {
            UserRoleEntity relation = new UserRoleEntity();
            relation.setUserId(userId);
            relation.setRoleId(roleId);
            userRoleMapper.insert(relation);
        }

        // ⑦ 关键一步：角色变了，该用户缓存的权限集合立刻作废。
        //    漏掉这行 → 用户被摘掉 ADMIN 后，30 分钟内仍能调用管理接口。
        //    放在事务内还有个好处：万一后续还需回滚，缓存会在下次读取时自动纠正。
        permissionService.refreshPermissions(userId);

        log.info("用户角色已更新：userId={}, roleIds={}", userId, distinctRoleIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void grantRole(Long userId, String roleCode) {
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(404, "用户不存在");
        }
        RoleEntity role = findRoleByCode(roleCode);
        if (role == null) {
            // 这里是 500 而不是 400：角色编码由服务端调用方（如店铺审核）指定，
            // 传进来一个不存在的编码属于程序配置错误，不是用户输入问题。
            throw new BusinessException(500, "角色不存在：" + roleCode);
        }
        attachRole(userId, role);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeRole(Long userId, String roleCode) {
        RoleEntity role = findRoleByCode(roleCode);
        if (role == null) {
            // 角色本身都不存在，用户自然不可能持有 —— 幂等成功，不报错
            return;
        }
        userRoleMapper.delete(Wrappers.<UserRoleEntity>lambdaQuery()
                .eq(UserRoleEntity::getUserId, userId)
                .eq(UserRoleEntity::getRoleId, role.getId()));

        permissionService.refreshPermissions(userId);
        log.info("已移除用户角色：userId={}, role={}", userId, roleCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindDefaultRole(Long userId) {
        RoleEntity role = findRoleByCode(DEFAULT_ROLE_CODE);

        if (role == null) {
            // 只记警告不抛异常：注册流程不应因为一个可选角色缺失而整体失败
            log.warn("默认角色 {} 不存在，用户 {} 注册成功但未绑定任何角色",
                    DEFAULT_ROLE_CODE, userId);
            return;
        }
        attachRole(userId, role);
    }

    /**
     * 幂等追加一条用户-角色关系。
     *
     * <p>先查 {@code exists} 再 insert，而不是直接 insert 然后捕获唯一键冲突：
     * 直接 insert 一旦撞 {@code uk_user_role}，在 MySQL 里会消耗一次自增 id，
     * 而且把「已存在」这个正常情况变成了异常流程，调用方还得去解析错误码。</p>
     */
    private void attachRole(Long userId, RoleEntity role) {
        boolean held = userRoleMapper.exists(Wrappers.<UserRoleEntity>lambdaQuery()
                .eq(UserRoleEntity::getUserId, userId)
                .eq(UserRoleEntity::getRoleId, role.getId()));
        if (held) {
            log.debug("用户已持有该角色，跳过：userId={}, role={}", userId, role.getCode());
            return;
        }

        UserRoleEntity relation = new UserRoleEntity();
        relation.setUserId(userId);
        relation.setRoleId(role.getId());
        userRoleMapper.insert(relation);

        permissionService.refreshPermissions(userId);
        log.info("已授予用户角色：userId={}, role={}", userId, role.getCode());
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private RoleEntity requireRole(Long id) {
        RoleEntity role = id == null ? null : roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(404, "角色不存在");
        }
        return role;
    }

    private boolean isBuiltIn(RoleEntity role) {
        return Integer.valueOf(1).equals(role.getBuiltIn());
    }

    private boolean codeExists(String code) {
        return roleMapper.exists(Wrappers.<RoleEntity>lambdaQuery().eq(RoleEntity::getCode, code));
    }

    private Long findRoleIdByCode(String code) {
        RoleEntity role = findRoleByCode(code);
        return role == null ? null : role.getId();
    }

    private RoleEntity findRoleByCode(String code) {
        return roleMapper.selectOne(
                Wrappers.<RoleEntity>lambdaQuery().eq(RoleEntity::getCode, code));
    }

    private Long findPermissionIdByCode(String code) {
        PermissionEntity permission = permissionMapper.selectOne(
                Wrappers.<PermissionEntity>lambdaQuery().eq(PermissionEntity::getCode, code));
        return permission == null ? null : permission.getId();
    }

    /**
     * 阻止「移除 role:permission」这一步操作。
     *
     * <p>只在角色<b>当前确实持有</b>该权限时才拦 —— 如果一个角色从来没有过它，
     * 那"不包含它"只是常态，不该报错。</p>
     */
    private void guardSelfLock(Long roleId, List<Long> newPermissionIds) {
        Long selfLockId = findPermissionIdByCode(SELF_LOCK_PERMISSION);
        if (selfLockId == null || newPermissionIds.contains(selfLockId)) {
            return;
        }
        boolean currentlyHolds = rolePermissionMapper.exists(
                Wrappers.<RolePermissionEntity>lambdaQuery()
                        .eq(RolePermissionEntity::getRoleId, roleId)
                        .eq(RolePermissionEntity::getPermissionId, selfLockId));
        if (currentlyHolds) {
            throw new BusinessException(400,
                    "不能移除 " + SELF_LOCK_PERMISSION + " 权限 —— 它是修改角色权限的唯一入口，"
                            + "移除后该角色将再也无法通过界面恢复任何权限");
        }
    }

    /**
     * 阻止管理员摘掉自己的 ADMIN 角色。
     *
     * <p>只在「被操作者就是当前登录用户」时生效。其他管理员可以正常调整你的角色，
     * 因为系统里还有别人能操作 —— 真正危险的是把自己变成最后一个无法自救的账号。</p>
     */
    private void guardNotRemovingOwnAdmin(Long userId, List<Long> newRoleIds) {
        if (!Objects.equals(userId, UserContext.getUserId())) {
            return;
        }
        boolean isAdminNow = roleMapper.selectCodesByUserId(userId).contains(ADMIN_ROLE_CODE);
        if (!isAdminNow) {
            return;
        }
        Long adminRoleId = findRoleIdByCode(ADMIN_ROLE_CODE);
        if (adminRoleId == null || !newRoleIds.contains(adminRoleId)) {
            throw new BusinessException(400,
                    "不能移除自己的 ADMIN 角色 —— 移除后将无法再进入管理后台，"
                            + "请让另一位管理员来调整你的角色");
        }
    }
}
