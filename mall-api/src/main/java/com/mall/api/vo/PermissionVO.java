package com.mall.api.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 权限 VO：管理端权限字典的一条记录，用于「给角色分配权限」的勾选面板。
 */
@Data
public class PermissionVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 权限编码，如 user:delete。前端勾选框的 value 用 id，回显展示用 code */
    private String code;

    /** 权限名称，如「删除用户」 */
    private String name;

    /** 类型：1 菜单，2 按钮，3 接口 */
    private Integer type;

    /** 排序值。前端按它排序即可让同一资源的权限聚在一起，无需额外分组接口 */
    private Integer sort;

    private Integer status;
}
