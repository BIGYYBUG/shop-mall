package com.mall.controller;

import com.mall.api.dto.AdminUserUpdateDTO;
import com.mall.api.dto.AssignRoleDTO;
import com.mall.api.dto.ResetPasswordDTO;
import com.mall.api.vo.PageVO;
import com.mall.api.vo.UserVO;
import com.mall.common.annotation.RequiresPermission;
import com.mall.common.result.Result;
import com.mall.service.rbac.RoleService;
import com.mall.service.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 - 用户管理。
 *
 * <p>整个类的路径前缀是 {@code /admin}，与面向普通用户的 {@code /user} 从 URL 上就分开了。
 * 这样做有两个好处：网关层可以直接按前缀配置限流和审计策略；
 * 将来拆微服务时，{@code /admin/**} 天然对应后台管理服务。</p>
 *
 * <p><b>注意每个方法上的 {@code @RequiresPermission}</b>：这就是 RBAC 落地的地方。
 * 校验逻辑全部在 {@code PermissionAspect} 里，本类只负责声明「这个接口需要什么权限」。</p>
 */
@RestController
@RequestMapping("/admin/user")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;
    private final RoleService roleService;

    /**
     * 用户分页列表
     *
     * <p>{@code @RequestParam(defaultValue = ...)} 让前端不传参也能正常工作，
     * 省掉一堆 if 判空。</p>
     */
    @GetMapping("/page")
    @RequiresPermission("user:list")
    public Result<PageVO<UserVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                       @RequestParam(defaultValue = "10") long pageSize,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Integer status) {
        return Result.success(userService.pageUsers(pageNum, pageSize, keyword, status));
    }

    /**
     * 用户详情
     */
    @GetMapping("/{id}")
    @RequiresPermission("user:detail")
    public Result<UserVO> detail(@PathVariable("id") Long id) {
        return Result.success(userService.getUserById(id));
    }

    /**
     * 修改用户基本信息（不含密码、用户名）
     */
    @PutMapping("/{id}")
    @RequiresPermission("user:update")
    public Result<Void> update(@PathVariable("id") Long id,
                               @Valid @RequestBody AdminUserUpdateDTO dto) {
        userService.updateUser(id, dto);
        return Result.success("修改成功", null);
    }

    /**
     * 启用 / 禁用用户
     *
     * @param status 0 禁用，1 正常
     */
    @PutMapping("/{id}/status")
    @RequiresPermission("user:status")
    public Result<Void> updateStatus(@PathVariable("id") Long id,
                                     @RequestParam("status") Integer status) {
        userService.updateStatus(id, status);
        return Result.success("状态已更新", null);
    }

    /**
     * 重置用户密码
     */
    @PutMapping("/{id}/password")
    @RequiresPermission("user:password")
    public Result<Void> resetPassword(@PathVariable("id") Long id,
                                      @Valid @RequestBody ResetPasswordDTO dto) {
        userService.resetPassword(id, dto.newPassword());
        return Result.success("密码已重置", null);
    }

    /**
     * 删除用户（逻辑删除）
     */
    @DeleteMapping("/{id}")
    @RequiresPermission("user:delete")
    public Result<Void> delete(@PathVariable("id") Long id) {
        userService.deleteUser(id);
        return Result.success("删除成功", null);
    }

    /**
     * 查询用户已绑定的角色 ID，用于角色多选框回显
     */
    @GetMapping("/{id}/roles")
    @RequiresPermission("user:detail")
    public Result<List<Long>> rolesOfUser(@PathVariable("id") Long id) {
        return Result.success(roleService.listRoleIdsByUserId(id));
    }

    /**
     * 分配用户角色（全量覆盖）
     *
     * <p>需要 {@code role:assign} 而不是 {@code user:update} ——
     * 改角色等于改权限，属于提权操作，必须单独授权。
     * 把提权能力藏在普通编辑权限里，是最常见的权限设计失误。</p>
     */
    @PutMapping("/{id}/roles")
    @RequiresPermission("role:assign")
    public Result<Void> assignRoles(@PathVariable("id") Long id,
                                    @Valid @RequestBody AssignRoleDTO dto) {
        roleService.assignRoles(id, dto.roleIds());
        return Result.success("角色已更新", null);
    }
}
