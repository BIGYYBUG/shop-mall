package com.mall.controller;

import com.mall.api.vo.PermissionVO;
import com.mall.common.annotation.RequiresPermission;
import com.mall.common.result.Result;
import com.mall.service.rbac.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 - 权限字典查询。
 *
 * <h3>为什么只有 GET，没有 POST / PUT / DELETE</h3>
 *
 * <p>这不是「还没做」，而是<b>刻意的设计</b>。权限编码与代码是强耦合的：
 * {@code user:list} 之所以存在，是因为 {@code AdminUserController} 上写了
 * {@code @RequiresPermission("user:list")}。</p>
 *
 * <table border="1">
 *   <caption>两类数据的本质区别</caption>
 *   <tr><th></th><th>角色</th><th>权限</th></tr>
 *   <tr><td>性质</td><td>运维数据（权限的打包分组）</td><td>代码契约（接口能力的声明）</td></tr>
 *   <tr><td>谁能改</td><td>管理员随时增删改</td><td>只能随代码发布改</td></tr>
 *   <tr><td>在界面上新建的后果</td><td>可以正常使用</td><td>没有任何接口引用它，纯死数据</td></tr>
 *   <tr><td>在界面上删除的后果</td><td>持有者少一组权限</td><td>对应接口永远 403，代码里看不出异常</td></tr>
 * </table>
 *
 * <p>所以权限码一律通过 SQL 脚本发布（当前是 {@code docs/sql/06_rbac_complete.sql}），
 * 界面只负责读取展示。想要「增删权限」的正确做法是：改代码加注解 → 写一条
 * {@code INSERT INTO mall_permission} → 跑脚本。这个过程本来就该走发布流程。</p>
 */
@RestController
@RequestMapping("/admin/permission")
@RequiredArgsConstructor
public class AdminPermissionController {

    private final PermissionService permissionService;

    /**
     * 权限字典全量列表，按 sort 排序（同一资源的权限天然聚在一起）。
     *
     * <p>共三十余条、基本不变，无需分页 —— 加分页只会让前端多写一层展开逻辑。</p>
     */
    @GetMapping("/list")
    @RequiresPermission("permission:list")
    public Result<List<PermissionVO>> list() {
        return Result.success(permissionService.listPermissions());
    }
}
