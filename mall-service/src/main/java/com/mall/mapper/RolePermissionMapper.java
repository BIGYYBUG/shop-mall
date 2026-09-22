package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.RolePermissionEntity;

/**
 * 角色-权限关联 Mapper。
 *
 * <p>只需要 BaseMapper 的通用 CRUD 就够：分配权限是「先按 role_id 物理删光、
 * 再逐条 insert」的全量覆盖语义，不需要任何自定义 SQL。</p>
 *
 * <p><b>为什么这里是物理删除而不是逻辑删除</b>：关联表没有 {@code deleted} 列。
 * 取消授权 = 删掉这条关系，若改用逻辑删除，重新授予同一权限时会撞
 * {@code uk_role_permission (role_id, permission_id)} 唯一索引 ——
 * 详见 {@link com.mall.entity.UserRoleEntity} 的注释。</p>
 */
public interface RolePermissionMapper extends BaseMapper<RolePermissionEntity> {
}
