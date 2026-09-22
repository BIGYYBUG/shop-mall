package com.mall.service.user;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.api.dto.AdminUserUpdateDTO;
import com.mall.api.dto.LoginDTO;
import com.mall.api.dto.RegisterDTO;
import com.mall.api.vo.LoginVO;
import com.mall.api.vo.PageVO;
import com.mall.api.vo.UserVO;
import com.mall.common.context.UserContext;
import com.mall.common.exception.BusinessException;
import com.mall.common.util.JwtUtil;
import com.mall.convert.user.UserVoFactory;
import com.mall.entity.UserEntity;
import com.mall.mapper.UserMapper;
import com.mall.service.rbac.PermissionService;
import com.mall.service.rbac.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

import static com.mall.common.util.TextUtils.trimToNull;

/**
 * 用户服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RoleService roleService;
    private final PermissionService permissionService;
    /** VO 装配统一走工厂，本类不再出现 new UserVO() / setXxx 的搬运代码 */
    private final UserVoFactory userVoFactory;

    // ==================================================================
    // 登录 / 注册 / 当前用户
    // ==================================================================

    @Override
    public UserVO getUserById(Long id) {
        UserEntity user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        return userVoFactory.create(user, roleService.listRoleCodesByUserId(id));
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        // ① 按用户名或手机号查用户（前端提示"用户名 / 手机号"）
        UserEntity user = userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery()
                        .eq(UserEntity::getUsername, dto.username())
                        .or()
                        .eq(UserEntity::getPhone, dto.username())
        );

        // ② 用户不存在与密码错误返回同一提示，避免暴露"哪些账号存在"
        if (user == null || !passwordEncoder.matches(dto.password(), user.getPassword())) {
            throw new BusinessException(400, "用户名或密码错误");
        }

        // ③ 检查账号状态
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(403, "账号已被禁用，请联系客服");
        }

        // ④ 取角色（直连数据库，令牌是对外凭据不能用缓存里的旧值）
        List<String> roleCodes = roleService.listRoleCodesByUserId(user.getId());

        // ⑤ 预热权限缓存：登录后就查好，后续每次接口鉴权都是内存/Redis 命中，
        //    不会出现"登录后第一个请求特别慢"的毛刺
        permissionService.refreshPermissions(user.getId());

        // ⑥ 签发令牌，角色一并写入 payload
        String token = jwtUtil.createToken(user.getId(), user.getUsername(), roleCodes);

        log.info("用户登录成功：id={}, username={}, roles={}", user.getId(), user.getUsername(), roleCodes);
        return new LoginVO(token, userVoFactory.create(user, roleCodes));
    }

    @Override
    public Long register(RegisterDTO dto) {
        // ① 用户名唯一性校验
        Long count = userMapper.selectCount(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, dto.username())
        );
        if (count != null && count > 0) {
            throw new BusinessException(400, "用户名已被占用");
        }

        // ② 密码加密后落库
        UserEntity user = new UserEntity();
        user.setUsername(dto.username());
        user.setPassword(passwordEncoder.encode(dto.password()));
        user.setPhone(trimToNull(dto.phone()));
        user.setEmail(trimToNull(dto.email()));
        user.setNickname(StringUtils.hasText(dto.nickname()) ? dto.nickname() : dto.username());
        user.setStatus(1);

        userMapper.insert(user);

        // ③ 绑定默认角色。没有这一步，新注册用户连自己的详情都查不了
        roleService.bindDefaultRole(user.getId());

        log.info("用户注册成功：id={}, username={}", user.getId(), user.getUsername());
        return user.getId();
    }

    @Override
    public UserVO getCurrentUser() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        return getUserById(userId);
    }

    // ==================================================================
    // 管理端
    // ==================================================================

    @Override
    public PageVO<UserVO> pageUsers(long pageNum, long pageSize, String keyword, Integer status) {
        // 防御非法分页参数：pageSize 过大会把数据库拖垮，这是最常见的接口层攻击面
        long safePageNum = Math.max(pageNum, 1);
        long safePageSize = Math.min(Math.max(pageSize, 1), 100);

        IPage<UserEntity> page = userMapper.selectPage(
                new Page<>(safePageNum, safePageSize),
                Wrappers.<UserEntity>lambdaQuery()
                        // and(...) 把三个 like 用括号包起来，
                        // 否则会变成 status = ? AND a LIKE ? OR b LIKE ? OR c LIKE ? —— 条件被拆散
                        .and(StringUtils.hasText(keyword), wrapper -> wrapper
                                .like(UserEntity::getUsername, keyword)
                                .or().like(UserEntity::getNickname, keyword)
                                .or().like(UserEntity::getPhone, keyword))
                        .eq(status != null, UserEntity::getStatus, status)
                        .orderByDesc(UserEntity::getId)
        );

        List<UserEntity> records = page.getRecords();
        // 一次 SQL 批量取回本页所有人的角色，避免每行查一次（N+1）
        Map<Long, List<String>> roleMap = roleService.mapRoleCodesByUserIds(
                records.stream().map(UserEntity::getId).toList());

        // 批量装配交给工厂：它内部把 userId → 角色的映射对上，本类不再关心拼装细节
        List<UserVO> voList = userVoFactory.createList(records, roleMap);

        return new PageVO<>(page.getTotal(), page.getPages(), page.getCurrent(), page.getSize(), voList);
    }

    @Override
    public void updateUser(Long id, AdminUserUpdateDTO dto) {
        requireUserExists(id);

        // 用 LambdaUpdateWrapper 而不是 updateById(entity)：
        // updateById 默认只更新非 null 字段，意味着「清空某个字段」这个操作做不到。
        // 显式 set 每个列，null 就是 null，语义明确。
        userMapper.update(null, Wrappers.<UserEntity>lambdaUpdate()
                .eq(UserEntity::getId, id)
                .set(UserEntity::getNickname, trimToNull(dto.nickname()))
                .set(UserEntity::getEmail, trimToNull(dto.email()))
                .set(UserEntity::getPhone, trimToNull(dto.phone()))
                .set(UserEntity::getAvatar, trimToNull(dto.avatar())));

        log.info("用户信息已修改：id={}", id);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(400, "状态值只能是 0（禁用）或 1（正常）");
        }
        requireUserExists(id);

        // 防呆：禁止把自己禁用。否则管理员一失手，全系统没人能再进后台
        if (id.equals(UserContext.getUserId()) && status == 0) {
            throw new BusinessException(400, "不能禁用当前登录的账号");
        }

        userMapper.update(null, Wrappers.<UserEntity>lambdaUpdate()
                .eq(UserEntity::getId, id)
                .set(UserEntity::getStatus, status));

        // 禁用后清掉权限缓存：避免他在缓存过期前继续调用接口
        if (status == 0) {
            permissionService.clearPermissions(id);
        }

        log.info("用户状态已变更：id={}, status={}", id, status);
    }

    @Override
    public void resetPassword(Long id, String newPassword) {
        requireUserExists(id);

        userMapper.update(null, Wrappers.<UserEntity>lambdaUpdate()
                .eq(UserEntity::getId, id)
                .set(UserEntity::getPassword, passwordEncoder.encode(newPassword)));

        // 密码变更不需要清权限缓存（权限没变），但应让所有已签发令牌失效。
        // 当前项目没有做令牌黑名单，这里留作阶段五「登录态治理」的改造点。
        log.info("用户密码已重置：id={}", id);
    }

    @Override
    public void deleteUser(Long id) {
        requireUserExists(id);

        if (id.equals(UserContext.getUserId())) {
            throw new BusinessException(400, "不能删除当前登录的账号");
        }

        // deleteById 在 @TableLogic 作用下实际执行 UPDATE ... SET deleted = id
        // （写本行 id 而不是 1，这样 uk_username(username, deleted) 允许同名账号反复注销重注）
        userMapper.deleteById(id);
        permissionService.clearPermissions(id);

        log.info("用户已被逻辑删除：id={}", id);
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private void requireUserExists(Long id) {
        if (id == null || userMapper.selectById(id) == null) {
            throw new BusinessException(404, "用户不存在");
        }
    }
}
