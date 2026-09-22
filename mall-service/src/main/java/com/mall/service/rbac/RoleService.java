package com.mall.service.rbac;

import com.mall.api.dto.RoleDTO;
import com.mall.api.dto.RoleUpdateDTO;
import com.mall.api.vo.RoleVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 角色服务：角色的增删改查、角色授权（用户↔角色、角色↔权限）。
 *
 * <h3>两条贯穿全类的铁律</h3>
 *
 * <p><b>① 任何改变「谁有什么权限」的写操作，必须同时刷新权限缓存。</b>
 * 本类有两个维度会改变权限：给用户换角色、给角色换权限。前者影响一个人，
 * 后者影响这个角色下的所有人。漏刷任意一边，都会让系统在一段时间内
 * 「按旧权限放行」—— 收权场景下这是安全漏洞，授权场景下这是功能故障。</p>
 *
 * <p><b>② 内置角色受保护。</b>{@code built_in = 1} 的角色不允许删除，
 * 也不允许改动编码。这是防止「管理员把 ADMIN 角色删掉，从此没人进得了后台」
 * 这类不可自救的事故。</p>
 */
public interface RoleService {

    /**
     * 查询所有启用中的角色（管理端下拉框用）。
     *
     * <p>轻量形态：<b>不查权限明细</b>，避免列表接口产生 N+1。</p>
     */
    List<RoleVO> listRoles();

    /**
     * 查询角色详情，附带已绑定的权限 ID 与权限编码（编辑弹窗回显用）。
     *
     * @param id 角色 ID
     * @return 含权限信息的角色 VO
     * @throws com.mall.common.exception.BusinessException 角色不存在时抛 404
     */
    RoleVO getRoleDetail(Long id);

    /**
     * 新增角色。
     *
     * @param dto 角色参数，编码需唯一且符合大写命名规范
     * @return 新角色 ID
     * @throws com.mall.common.exception.BusinessException 编码重复时抛 400
     */
    Long createRole(RoleDTO dto);

    /**
     * 修改角色。编码不可改（原因见 {@code RoleDTO} 类注释）。
     *
     * @param id  角色 ID
     * @param dto 可改字段：名称、描述、状态、排序
     */
    void updateRole(Long id, RoleUpdateDTO dto);

    /**
     * 删除角色（逻辑删除），并清理其权限绑定与用户绑定。
     *
     * <p><b>两道拒绝条件</b>：内置角色不可删；仍有用户持有的角色不可删。
     * 后者若不拦，{@code mall_user_role} 里会留下指向不存在角色的孤儿行，
     * 那些用户会"静默降权"且查不出任何报错。</p>
     *
     * @param id 角色 ID
     */
    void deleteRole(Long id);

    /**
     * 全量覆盖某角色的权限，并刷新该角色下所有用户的权限缓存。
     *
     * @param roleId        角色 ID
     * @param permissionIds 最终应持有的权限 ID；传空列表表示清空
     */
    void assignPermissions(Long roleId, List<Long> permissionIds);

    /**
     * 查询某用户的角色编码，直连数据库不读缓存。
     *
     * <p>用于登录签发令牌 —— 令牌是对外凭据，必须拿数据库的准确值，
     * 不能容忍缓存中的旧数据。</p>
     */
    List<String> listRoleCodesByUserId(Long userId);

    /**
     * 查询某用户已绑定的角色 ID 列表（回显角色多选框）
     */
    List<Long> listRoleIdsByUserId(Long userId);

    /**
     * 批量查询多个用户的角色编码，返回 userId → 角色编码列表 的映射。
     *
     * <p>专为管理端列表页设计，一次 SQL 取回全部，避免 N+1 查询。</p>
     */
    Map<Long, List<String>> mapRoleCodesByUserIds(Collection<Long> userIds);

    /**
     * 全量覆盖某用户的角色，并立即刷新其权限缓存。
     *
     * @param userId  用户 ID
     * @param roleIds 最终应持有的角色 ID；传空列表表示收回全部角色
     */
    void assignRoles(Long userId, List<Long> roleIds);

    /**
     * 追加一个角色（<b>不影响</b>该用户已有的其他角色）。
     *
     * <p><b>为什么不能直接用 {@link #assignRoles} 代替</b>：
     * {@code assignRoles} 是「全量覆盖」语义。想给用户"加一个 SELLER"就得先读出现有
     * 角色、拼上新角色、再整体提交 —— 这是一个读-改-写序列，两个请求并发时
     * 后提交的那次会覆盖前一次的授权（经典的 lost update）。
     * 平台审核店铺通过时正需要"加一个角色"，此时用追加语义既更安全，
     * 意图也更清楚。</p>
     *
     * <p>已持有该角色时幂等返回，不报错 —— 审核接口被重复调用不该失败。</p>
     *
     * @param userId   用户 ID
     * @param roleCode 角色编码
     */
    void grantRole(Long userId, String roleCode);

    /**
     * 移除一个角色（<b>不影响</b>该用户的其他角色）。
     *
     * <p>用于「店铺被驳回 / 冻结 → 收回 SELLER 角色」。未持有时幂等返回。</p>
     *
     * @param userId   用户 ID
     * @param roleCode 角色编码
     */
    void revokeRole(Long userId, String roleCode);

    /**
     * 给用户绑定默认角色（新注册用户、第三方首次登录时调用）。
     *
     * <p>默认角色编码由 {@link #DEFAULT_ROLE_CODE} 指定。若该角色不存在，
     * 只记警告不抛异常 —— 注册流程不应因为一个可选的角色缺失而整体失败。</p>
     *
     * @param userId 用户 ID
     */
    void bindDefaultRole(Long userId);

    /**
     * 默认角色编码。用常量而不是硬编码字符串，避免各处写法不一致。
     */
    String DEFAULT_ROLE_CODE = "USER";

    /**
     * 卖家角色编码。
     *
     * <p>它与 {@code shop.status == 1} 之间有一条必须维持的等价关系，
     * 详见 {@code ShopService} 的类注释。把编码集中定义在这里，
     * 是为了让"改角色编码"这件事只需要改一处 —— 散在各处的字面量
     * {@code "SELLER"} 是漏改的头号来源。</p>
     */
    String SELLER_ROLE_CODE = "SELLER";

    /**
     * 平台管理员角色编码。
     */
    String ADMIN_ROLE_CODE = "ADMIN";
}
