package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.UserRoleEntity;

/**
 * 用户-角色关联 Mapper。
 *
 * <p>只用到 BaseMapper 的通用方法：按 userId 条件删除、逐条插入。</p>
 */
public interface UserRoleMapper extends BaseMapper<UserRoleEntity> {
}
