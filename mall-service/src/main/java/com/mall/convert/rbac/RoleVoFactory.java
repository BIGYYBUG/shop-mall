package com.mall.convert.rbac;

import com.mall.api.vo.RoleVO;
import com.mall.convert.VoFactory;
import com.mall.entity.RoleEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 角色 VO 装配工厂。
 *
 * <h3>两个装配入口，对应两种响应形态</h3>
 * <ul>
 *   <li>{@link #create(RoleEntity)} —— 轻量形态。角色列表 / 下拉框用，
 *       不查权限，因此不会带来额外 SQL。</li>
 *   <li>{@link #createDetail(RoleEntity, List, List)} —— 详情形态。多带
 *       「已勾选的权限 ID」与「权限编码列表」，供编辑弹窗回显。</li>
 * </ul>
 *
 * <p>之所以不把权限字段塞进 {@code create} 里统一查：列表接口每多一个角色
 * 就要多一次权限查询，角色数量上去之后就是标准的 N+1。
 * 需要权限明细的场景（详情、编辑）就该显式调用带权限的那个方法，
 * 让调用方一眼看出"这次会多查一次库"。</p>
 */
@Component
public class RoleVoFactory implements VoFactory<RoleEntity, RoleVO> {

    @Override
    public RoleVO create(RoleEntity source) {
        if (source == null) {
            return null;
        }
        RoleVO vo = new RoleVO();
        vo.setId(source.getId());
        vo.setCode(source.getCode());
        vo.setName(source.getName());
        vo.setDescription(source.getDescription());
        vo.setBuiltIn(source.getBuiltIn());
        vo.setSort(source.getSort());
        vo.setStatus(source.getStatus());
        return vo;
    }

    /**
     * 装配角色详情，附带权限信息。
     *
     * @param source        角色实体
     * @param permissionIds 已绑定的权限 ID（勾选框 value）
     * @param codes         已绑定的权限编码（只读展示）
     */
    public RoleVO createDetail(RoleEntity source, List<Long> permissionIds, List<String> codes) {
        RoleVO vo = create(source);
        if (vo == null) {
            return null;
        }
        vo.setPermissionIds(permissionIds == null ? List.of() : permissionIds);
        vo.setPermissions(codes == null ? List.of() : codes);
        return vo;
    }
}
