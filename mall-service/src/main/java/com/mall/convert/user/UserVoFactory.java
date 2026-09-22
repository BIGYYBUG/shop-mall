package com.mall.convert.user;

import com.mall.api.vo.UserVO;
import com.mall.convert.VoFactory;
import com.mall.entity.UserEntity;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 用户 VO 装配工厂。
 *
 * <p><b>本项目原本有两份一模一样的用户装配代码</b>（{@code UserServiceImpl} 与
 * {@code SocialLoginServiceImpl} 各一份），现在统一到这里。这也是引入工厂最直接的收益：
 * 想加一个"最后登录时间"字段，只改这一个类，而不是满项目找 {@code new UserVO()}。</p>
 *
 * <p><b>安全约定</b>：本类只装配白名单字段，{@code password}、{@code openid}、
 * {@code unionid}、{@code deleted} 一律不出现在 VO 中。走工厂的另一个好处正是
 * ——"哪些字段能出到前端"这件事被收敛成一个可审查的点，而不是散在 N 处。</p>
 */
@Component
public class UserVoFactory implements VoFactory<UserEntity, UserVO> {

    /**
     * 不带角色信息的装配。
     *
     * <p>能拿到 {@link UserEntity} 但拿不到角色时用（例如只查了用户表）。
     * 角色返回空集合而不是 null，避免前端到处判空。</p>
     */
    @Override
    public UserVO create(UserEntity source) {
        return create(source, List.of());
    }

    /**
     * 带角色信息的装配 —— 登录、详情、列表都走这个重载。
     *
     * @param roles 角色 code 列表，可为 null
     */
    public UserVO create(UserEntity source, Collection<String> roles) {
        if (source == null) {
            return null;
        }
        UserVO vo = new UserVO();
        vo.setId(source.getId());
        vo.setUsername(source.getUsername());
        vo.setPhone(source.getPhone());
        vo.setEmail(source.getEmail());
        vo.setNickname(source.getNickname());
        vo.setAvatar(source.getAvatar());
        vo.setStatus(source.getStatus());
        vo.setRoles(roles == null ? List.of() : List.copyOf(roles));
        vo.setCreateTime(source.getCreateTime());
        return vo;
    }

    /**
     * 批量装配（用户分页场景）。
     *
     * <p>刻意要求调用方先把「用户 ID → 角色列表」的映射整体查出来再传进来，
     * 而不是每行查一次库。这是避免 N+1 查询的关键 —— 一页 20 个用户，
     * 逐行查角色就是 20 次 SQL。</p>
     *
     * @param users              本页用户
     * @param roleCodesByUserId  userId → 角色 code 列表，可为 null
     */
    public List<UserVO> createList(List<UserEntity> users, Map<Long, List<String>> roleCodesByUserId) {
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        return users.stream()
                .map(user -> create(user, roleCodesByUserId == null
                        ? List.of()
                        : roleCodesByUserId.getOrDefault(user.getId(), List.of())))
                .toList();
    }
}
