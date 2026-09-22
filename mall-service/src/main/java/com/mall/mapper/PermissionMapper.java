package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.PermissionEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 权限 Mapper。
 *
 * <p>核心方法 {@link #selectCodesByUserId} 是一条四表连接的 SQL：
 * 用户 → 用户角色 → 角色 → 角色权限 → 权限。
 * 这条 SQL 已经写进 XML，因为 MyBatis-Plus 的 Wrapper 只能表达单表条件，
 * 多表连接必须手写。</p>
 */
public interface PermissionMapper extends BaseMapper<PermissionEntity> {

    /**
     * 查询用户实际生效的权限编码（去重）
     *
     * <p>「实际生效」的含义：用户的角色未被禁用、权限本身未被禁用、且两边都未被逻辑删除。</p>
     *
     * @param userId 用户 ID
     * @return 权限编码列表；无权限时返回空列表
     */
    List<String> selectCodesByUserId(@Param("userId") Long userId);
}
