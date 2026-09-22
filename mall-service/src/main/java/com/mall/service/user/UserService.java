package com.mall.service.user;

import com.mall.api.dto.AdminUserUpdateDTO;
import com.mall.api.dto.LoginDTO;
import com.mall.api.dto.RegisterDTO;
import com.mall.api.vo.LoginVO;
import com.mall.api.vo.PageVO;
import com.mall.api.vo.UserVO;

/**
 * 用户服务接口。
 */
public interface UserService {

    /**
     * 根据 ID 查询用户（含角色）
     */
    UserVO getUserById(Long id);

    /**
     * 登录：校验账号密码，签发携带角色的令牌，并预热权限缓存
     */
    LoginVO login(LoginDTO dto);

    /**
     * 注册：密码加密后落库，并绑定默认 USER 角色
     *
     * @return 新用户 ID
     */
    Long register(RegisterDTO dto);

    /**
     * 查询当前登录用户（依赖 UserContext 中由拦截器写入的用户 ID）
     */
    UserVO getCurrentUser();

    // ------------------------------------------------------------------
    // 以下为管理端能力，调用方必须先通过 user:* 权限校验
    // ------------------------------------------------------------------

    /**
     * 分页查询用户
     *
     * @param pageNum  页码，从 1 开始
     * @param pageSize 每页条数
     * @param keyword  关键字，匹配用户名 / 昵称 / 手机号，可为空
     * @param status   状态过滤，null 表示不限
     */
    PageVO<UserVO> pageUsers(long pageNum, long pageSize, String keyword, Integer status);

    /**
     * 修改用户基本信息（昵称 / 邮箱 / 手机号 / 头像）
     */
    void updateUser(Long id, AdminUserUpdateDTO dto);

    /**
     * 启用 / 禁用用户
     *
     * @param status 0 禁用，1 正常
     */
    void updateStatus(Long id, Integer status);

    /**
     * 重置用户密码（管理员操作，不校验原密码）
     */
    void resetPassword(Long id, String newPassword);

    /**
     * 逻辑删除用户
     *
     * <p>用逻辑删除而非物理删除，是为了保留关联数据（订单、日志）的可追溯性。</p>
     */
    void deleteUser(Long id);
}
