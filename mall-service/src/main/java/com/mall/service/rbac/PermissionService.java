package com.mall.service.rbac;

import com.mall.api.vo.PermissionVO;
import com.mall.common.spi.PermissionChecker;

import java.util.List;
import java.util.Set;

/**
 * 权限服务：RBAC 中「读权限」的唯一入口。
 *
 * <p>继承 {@link PermissionChecker} 是为了让 mall-common 里的切面能够依赖它 ——
 * 切面只知道 API-common 的契约，不知道 Redis、Mapper 的存在。</p>
 */
public interface PermissionService extends PermissionChecker {

    /**
     * 强制刷新某用户的权限缓存。
     *
     * <p><b>什么时候必须调用</b>：</p>
     * <ul>
     *   <li>给用户分配 / 移除角色之后</li>
     *   <li>给角色增删权限之后（影响该角色下所有人）</li>
     *   <li>禁用角色或权限之后</li>
     * </ul>
     * <p>漏调的后果是：用户已经失去了某权限，但在缓存过期前（默认 30 分钟）
     * 依然能调通接口 —— 这是权限系统最典型的安全漏洞。</p>
     */
    void refreshPermissions(Long userId);

    /**
     * 刷新「所有持有该角色的用户」的权限缓存。
     *
     * <p><b>为什么必须有这个方法</b>：给角色增删权限，影响的是这个角色下的每一个人，
     * 而不是单个用户。只调 {@link #refreshPermissions(Long)} 刷不到他们 ——
     * 后果是权限已经收回，但这些人手里的令牌在 TTL（30 分钟）内仍能调通接口。</p>
     *
     * <p>反过来，给角色<b>加</b>权限漏刷同样有害：运营在界面上勾上了新权限，
     * 用户那边却要等半小时才生效，会被当成"功能没上线"反复提单。</p>
     *
     * <p><b>规模提示</b>：实现是「查出全部用户 ID → 逐个刷新」。
     * 角色下挂几万人时不合适，届时应改为按角色维度缓存权限集合
     * （key 用 roleId），或走消息广播让各节点自行失效。
     * 当前项目体量下这个实现是清晰且够用的。</p>
     *
     * @param roleId 角色 ID
     */
    void refreshByRoleId(Long roleId);

    /**
     * 权限字典全量查询（管理端「给角色分配权限」的勾选面板用）。
     *
     * <h3>为什么权限字典只有读、没有写</h3>
     *
     * <p>权限编码与代码是<b>强耦合</b>的：{@code user:list} 之所以有意义，
     * 是因为 {@code AdminUserController} 上写了 {@code @RequiresPermission("user:list")}。
     * 因此：</p>
     * <ul>
     *   <li>在界面上<b>新建</b>一个权限码 → 没有任何接口引用它，是纯粹的死数据；</li>
     *   <li>在界面上<b>删除</b>一个权限码 → 对应接口的校验永远失败，
     *       表现为"某个按钮突然 403"，而代码里看不出任何异常。</li>
     * </ul>
     * <p>所以权限码必须随代码发布一起走 SQL 脚本（见 {@code docs/sql/06_rbac_complete.sql}），
     * 界面只提供读取。角色可以自由增删改，权限不行 —— 这是两者本质的区别：
     * 角色是运维数据，权限是代码契约。</p>
     */
    List<PermissionVO> listPermissions();

    /**
     * 只清除缓存，不重新加载。
     */
    void clearPermissions(Long userId);

    /**
     * 当前用户是否拥有指定权限（给业务代码做细粒度判断用，非接口级拦截场景）
     */
    boolean hasPermission(Long userId, String permissionCode);

    /**
     * 当前用户是否拥有任意一个指定权限
     */
    boolean hasAnyPermission(Long userId, Set<String> permissionCodes);
}
