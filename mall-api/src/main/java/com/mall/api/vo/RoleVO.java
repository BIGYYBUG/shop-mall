package com.mall.api.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 角色 VO。
 *
 * <h3>两种用法的字段差异（有意为之）</h3>
 *
 * <p>列表接口 {@code GET /admin/role/list} 只为下拉框/表格提供 {@code id/code/name}
 * 等轻量字段，{@link #permissionIds} 与 {@link #permissions} 保持 null ——
 * 下拉框不需要权限明细，为每个角色都查一次权限集合纯属浪费。</p>
 *
 * <p>详情接口 {@code GET /admin/role/{id}} 才把两个权限字段填满，供编辑弹窗回显：
 * <ul>
 *   <li>{@link #permissionIds} 给勾选框用（value 是 id）</li>
 *   <li>{@link #permissions} 给只读展示用（人类可读的权限编码列表）</li>
 * </ul>
 * 两者同时给出，是为了避免前端在「已勾选 id」和「展示编码」之间再做一次映射。</p>
 *
 * <p>{@code @JsonInclude(NON_NULL)} 让轻量响应里干脆不出现这两个 key，
 * 而不是输出 {@code "permissions": null} —— 前端可以据此区分「没查」和「查了但没有」。</p>
 */
@Data
public class RoleVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 角色编码，如 ADMIN */
    private String code;

    /** 角色名称，如「超级管理员」 */
    private String name;

    private String description;

    /**
     * 是否内置角色：0 自定义，1 内置。
     * 前端据此把「删除」按钮置灰 —— 这只是体验层拦截，真正的拒止在后端。
     */
    private Integer builtIn;

    /** 排序值 */
    private Integer sort;

    private Integer status;

    /** 该角色已绑定的权限 ID 列表。仅详情接口返回 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Long> permissionIds;

    /** 该角色已绑定的权限编码列表。仅详情接口返回 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> permissions;
}
