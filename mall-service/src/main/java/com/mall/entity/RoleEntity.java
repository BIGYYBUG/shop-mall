package com.mall.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色实体，对应表 mall_role。
 *
 * <p>角色是「权限的集合」，本身不代表任何具体能力 —— 判断能不能做某事，
 * 永远看权限编码，不看角色编码。角色只是给运维用的批量授权单位。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_role")
public class RoleEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 角色编码，如 ADMIN / USER。程序里判断用这个，比中文名稳定 */
    private String code;

    /** 角色名称，如「超级管理员」。仅用于展示 */
    private String name;

    /** 描述 */
    private String description;

    /**
     * 是否内置角色：0 自定义，1 内置。
     *
     * <p>内置角色（ADMIN / SELLER / USER）禁止删除、禁止改 code。这不是洁癖 ——
     * 管理员若在界面上把 ADMIN 角色删掉，{@code mall_role_permission} 的关系随之清空，
     * 系统将再没有任何人能进入管理后台，只能改数据库救场。数据库层预留这个标记，
     * 让「把自己锁在门外」从可能变成不可能。</p>
     */
    private Integer builtIn;

    /** 排序值，越小越靠前。下拉框顺序靠它，不依赖自增 id 碰巧的先后 */
    private Integer sort;

    /** 状态：0 禁用，1 正常。禁用后该角色携带的权限不再生效 */
    private Integer status;
}
