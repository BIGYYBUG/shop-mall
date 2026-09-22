package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.RoleEntity;
import com.mall.mapper.model.UserRoleCode;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 角色 Mapper。
 *
 * <p>继承 BaseMapper 拿到通用 CRUD；跨表查询另定义方法 + 写 XML。</p>
 */
public interface RoleMapper extends BaseMapper<RoleEntity> {

    /**
     * 查询某个用户拥有的、且处于启用状态的角色编码
     *
     * @param userId 用户 ID
     * @return 角色编码列表，如 ["ADMIN"]；无角色时返回空列表
     */
    List<String> selectCodesByUserId(@Param("userId") Long userId);

    /**
     * 批量查询多个用户的角色编码（一次 SQL 解决，避免 N+1）
     *
     * <p>管理端用户列表要显示每人角色。若对每行都查一次，10 条数据就是 11 次
     * 数据库往返 —— 这就是经典的 N+1 问题。正确做法是收集所有 userId，
     * 用 {@code IN} 一次查回，再在内存里按 userId 分组。</p>
     *
     * @param userIds 用户 ID 集合，调用方需保证非空（空集合会导致 SQL 语法错误）
     * @return userId + roleCode 的扁平列表
     */
    List<UserRoleCode> selectUserRoleCodes(@Param("userIds") Collection<Long> userIds);

    /**
     * 查询持有某角色的全部用户 ID（只算未删除的用户）。
     *
     * <p><b>用途</b>：给角色增删权限之后，必须让「所有持有该角色的人」的权限缓存
     * 立刻失效。漏掉这一步，收权后这些人还能在 TTL（30 分钟）内继续调用接口 ——
     * 这是 RBAC 最典型、也最难通过人工测试发现的安全漏洞。
     * 一次查出用户 ID 列表再逐个刷新，避免在循环里查库。</p>
     *
     * @param roleId 角色 ID
     * @return 用户 ID 列表；无人持有时返回空列表
     */
    List<Long> selectUserIdsByRoleId(@Param("roleId") Long roleId);

    /**
     * 统计持有某角色的未删除用户数量。
     *
     * <p><b>用途</b>：删除角色前的占用检查。直接删角色会让 {@code mall_user_role}
     * 里留下指向不存在角色 ID 的孤儿行 —— 这些用户后续任何一次权限计算都拿不到
     * 该角色的权限，表面上"静默降权"，排查时却查不出任何报错。
     * 与其事后清理，不如在删除入口就拒绝。</p>
     *
     * @param roleId 角色 ID
     * @return 持有该角色的用户数
     */
    long countUsersByRoleId(@Param("roleId") Long roleId);
}
