package com.mall.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修改角色参数。
 *
 * <p>与 {@link RoleDTO} 的唯一区别是<b>没有 code</b> —— 角色编码创建后不可变，
 * 原因见 {@link RoleDTO} 的类注释。与其在更新时静默忽略客户端传来的 code
 * （调用方会以为自己改成功了），不如让这个字段根本不存在于契约里。</p>
 */
public record RoleUpdateDTO(

        @NotBlank(message = "角色名称不能为空")
        @Size(max = 64, message = "角色名称不能超过 64 个字符")
        String name,

        @Size(max = 255, message = "角色描述不能超过 255 个字符")
        String description,

        @NotNull(message = "状态不能为空")
        @Min(value = 0, message = "状态只能是 0(禁用) 或 1(正常)")
        @Max(value = 1, message = "状态只能是 0(禁用) 或 1(正常)")
        Integer status,

        @Min(value = 0, message = "排序值不能为负")
        Integer sort
) {
}
