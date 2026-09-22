package com.mall.social;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.api.vo.LoginVO;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/**
 * 第三方登录服务实现。
 *
 * <p><b>设计要点：第三方账号与本项目账号是「一套账号体系」。</b>
 * 微信登录成功后拿到的依然是本项目的 JWT，后续所有接口的鉴权流程完全一致。
 * 第三方登录只是「另一种证明你是谁」的方式，登录之后的链路与账号密码登录毫无区别 ——
 * 这是把认证方式与授权体系解耦带来的好处。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialLoginServiceImpl implements SocialLoginService {

    /** 自动注册的第三方用户，用户名加这个前缀以便与手动注册用户区分 */
    private static final String THIRD_PARTY_USERNAME_PREFIX = "wx_";

    /** 用户名长度上限（对应 mall_user.username varchar(64)） */
    private static final int USERNAME_MAX_LENGTH = 64;

    /**
     * 注入 List：Spring 会把容器里所有 SocialLoginProvider 实现按顺序注入。
     * 将来新增 QQ / 支付宝登录，只需加一个 @Component，本类无需改动。
     */
    private final List<SocialLoginProvider> providers;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RoleService roleService;
    private final PermissionService permissionService;
    /** 与 UserServiceImpl 共用同一个装配工厂 —— 用户 VO 只有一处装配代码 */
    private final UserVoFactory userVoFactory;

    @Override
    public List<String> listAvailableSources() {
        return providers.stream()
                .filter(SocialLoginProvider::configured)
                .map(SocialLoginProvider::source)
                .toList();
    }

    @Override
    public String buildAuthorizeUrl(String source, String state) {
        return resolve(source).buildAuthorizeUrl(state);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO login(String source, String code) {
        // ① 找渠道 → 用 code 换用户信息（这两步都在 Provider 内完成）
        SocialUserInfo info = resolve(source).getUserInfo(code);

        // ② 按 openid 找本项目账号
        UserEntity user = userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getOpenid, info.openid()));

        boolean newUser = false;
        if (user == null) {
            user = createThirdPartyUser(info);
            newUser = true;
        }

        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(403, "账号已被禁用，请联系客服");
        }

        // ③ 后续流程与账号密码登录完全一致：取角色 → 预热权限 → 签发令牌
        List<String> roleCodes = roleService.listRoleCodesByUserId(user.getId());
        permissionService.refreshPermissions(user.getId());
        String token = jwtUtil.createToken(user.getId(), user.getUsername(), roleCodes);

        log.info("第三方登录成功：source={}, userId={}, isNewUser={}", source, user.getId(), newUser);
        return new LoginVO(token, userVoFactory.create(user, roleCodes));
    }

    /**
     * 首次使用第三方登录时自动创建本地账号。
     *
     * <p><b>为什么密码字段要写一个随机哈希，而不是留空？</b>
     * 数据库该列是 NOT NULL。更重要的是：留空或存空串，一旦密码校验逻辑出现
     * 「空明文能匹配空哈希」的缺陷，这个账号就成了任何人可登录的后门。
     * 写入一个随机的、无人知晓原值的 BCrypt 哈希，等于把「密码登录」这条路彻底堵死。</p>
     */
    private UserEntity createThirdPartyUser(SocialUserInfo info) {
        UserEntity user = new UserEntity();
        user.setUsername(buildUniqueUsername(info.openid()));
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setNickname(StringUtils.hasText(info.nickname()) ? info.nickname() : "微信用户");
        user.setAvatar(info.avatar());
        user.setOpenid(info.openid());
        user.setUnionid(info.unionid());
        user.setStatus(1);
        userMapper.insert(user);

        // 绑定默认角色，否则新用户登录后拿不到任何权限
        roleService.bindDefaultRole(user.getId());

        log.info("第三方用户已自动注册：userId={}, source={}", user.getId(), info.source());
        return user;
    }

    /**
     * 生成不冲突的用户名。
     *
     * <p>正常情况直接用 {@code wx_ + openid} 即可。但万一用户手动注册过同名账号，
     * 插入会撞唯一索引 uk_username。这里做一次存在性检查并加随机后缀兜底 ——
     * 边界情况不处理，线上就会以 500 的形式暴露出来。</p>
     */
    private String buildUniqueUsername(String openid) {
        String base = THIRD_PARTY_USERNAME_PREFIX + openid;
        if (base.length() > USERNAME_MAX_LENGTH) {
            base = base.substring(0, USERNAME_MAX_LENGTH);
        }

        Long exists = userMapper.selectCount(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, base));
        if (exists == null || exists == 0) {
            return base;
        }

        String suffix = "_" + UUID.randomUUID().toString().substring(0, 8);
        int keep = USERNAME_MAX_LENGTH - suffix.length();
        return (base.length() > keep ? base.substring(0, keep) : base) + suffix;
    }

    private SocialLoginProvider resolve(String source) {
        if (!StringUtils.hasText(source)) {
            throw new BusinessException(400, "登录渠道不能为空");
        }
        return providers.stream()
                .filter(provider -> provider.source().equalsIgnoreCase(source))
                .findFirst()
                .orElseThrow(() -> new BusinessException(400, "不支持的第三方登录渠道：" + source));
    }
}
