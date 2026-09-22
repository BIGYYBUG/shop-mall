package com.mall.controller;

import com.mall.api.dto.AssignPermissionDTO;
import com.mall.api.dto.RoleDTO;
import com.mall.api.dto.RoleUpdateDTO;
import com.mall.api.vo.RoleVO;
import com.mall.common.annotation.RequiresPermission;
import com.mall.common.result.Result;
import com.mall.service.rbac.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 - 角色管理。
 *
 * <p>本类只做四件事：取参 → 校验 → 调服务 → 包 {@code Result}。
 * 角色的业务规则（内置角色保护、占用检查、缓存刷新）全部在
 * {@link RoleService} 里，Controller 不承担任何判断 —— 这样同样的规则
 * 在定时任务、消息消费等非 HTTP 入口也能复用。</p>
 *
 * <h3>接口清单</h3>
 * <pre>
 *   GET    /admin/role/list              role:list        角色列表（轻量，无权限明细）
 *   GET    /admin/role/{id}              role:detail      角色详情（含已绑权限，用于回显）
 *   POST   /admin/role                   role:create      新增角色
 *   PUT    /admin/role/{id}              role:update      修改角色（编码不可改）
 *   DELETE /admin/role/{id}              role:delete      删除角色
 *   PUT    /admin/role/{id}/permissions  role:permission  全量覆盖该角色的权限
 * </pre>
 *
 * <p>注意「分配用户角色」不在这里，它在 {@code AdminUserController} 的
 * {@code PUT /admin/user/{id}/roles}，权限码是 {@code role:assign} ——
 * 被操作的主体是「用户」，所以挂在用户接口下更符合直觉。</p>
 */
@RestController
@RequestMapping("/admin/role")
@RequiredArgsConstructor
public class AdminRoleController {

    private final RoleService roleService;

    /**
     * 角色列表，用于用户角色分配的下拉/多选框。
     *
     * <p>刻意不返回权限明细：下拉框不需要它，而每个角色多查一次权限就是 N+1。</p>
     */
    @GetMapping("/list")
    @RequiresPermission("role:list")
    public Result<List<RoleVO>> list() {
        return Result.success(roleService.listRoles());
    }

    /**
     * 角色详情，带上「已绑定的权限 ID」与「权限编码」，供编辑弹窗回显。
     */
    @GetMapping("/{id}")
    @RequiresPermission("role:detail")
    public Result<RoleVO> detail(@PathVariable("id") Long id) {
        return Result.success(roleService.getRoleDetail(id));
    }

    /**
     * 新增角色。
     *
     * @return 新角色 ID
     */
    @PostMapping
    @RequiresPermission("role:create")
    public Result<Long> create(@Valid @RequestBody RoleDTO dto) {
        return Result.success("角色创建成功", roleService.createRole(dto));
    }

    /**
     * 修改角色。编码不在可改范围内，所以参数类型是 {@link RoleUpdateDTO} 而非 RoleDTO。
     */
    @PutMapping("/{id}")
    @RequiresPermission("role:update")
    public Result<Void> update(@PathVariable("id") Long id,
                               @Valid @RequestBody RoleUpdateDTO dto) {
        roleService.updateRole(id, dto);
        return Result.success("角色已更新", null);
    }

    /**
     * 删除角色。内置角色与仍被用户持有的角色会被拒绝，具体原因在服务层。
     */
    @DeleteMapping("/{id}")
    @RequiresPermission("role:delete")
    public Result<Void> delete(@PathVariable("id") Long id) {
        roleService.deleteRole(id);
        return Result.success("角色已删除", null);
    }

    /**
     * 给角色分配权限 —— <b>全量覆盖</b>语义，提交什么就是最终结果，空数组表示清空。
     *
     * <p>这是 RBAC 里「角色 → 权限」这一环唯一的写入口。改完会立即刷新
     * 该角色下所有用户的权限缓存，不需要等 TTL 过期。</p>
     */
    @PutMapping("/{id}/permissions")
    @RequiresPermission("role:permission")
    public Result<Void> assignPermissions(@PathVariable("id") Long id,
                                          @Valid @RequestBody AssignPermissionDTO dto) {
        roleService.assignPermissions(id, dto.permissionIds());
        return Result.success("权限已更新", null);
    }
}
