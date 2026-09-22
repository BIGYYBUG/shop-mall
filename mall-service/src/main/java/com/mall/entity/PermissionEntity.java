package com.mall.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权限实体，对应表 mall_permission。
 *
 * <p>权限编码采用 {@code 资源:动作} 两段式命名，如 {@code user:delete}。
 * 这个格式不是随便定的 —— 后面做网关鉴权、菜单树、按钮级控制时，
 * 都能靠前缀 {@code user:} 直接过滤出一组权限。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_permission")
public class PermissionEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 权限标识，如 user:delete */
    private String code;

    /** 权限名称，如「删除用户」 */
    private String name;

    /** 类型：1 菜单，2 按钮，3 接口。当前只用到 3 */
    private Integer type;

    /**
     * 排序值，越小越靠前。
     *
     * <p>约定「十位即资源分组」：10xx user / 20xx role / 30xx permission /
     * 40xx product / 50xx shop / 60xx seller / 90xx 基础设施。
     * 管理端因此不需要额外的分组表，直接按 sort 排就能把同一资源的权限聚在一起。</p>
     */
    private Integer sort;

    /** 状态：0 禁用，1 正常 */
    private Integer status;
}
