package com.mall.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增角色参数。
 *
 * <p><b>为什么更新角色不共用这个类</b>：{@code code} 只在创建时可写，
 * 创建后不可更改。原因是角色编码会被写进 JWT 的 {@code roles} 声明，
 * 也会被前端 {@code hasRole('ADMIN')} 这类判断引用；一旦允许改名，
 * 所有已签发的旧令牌里仍然是旧编码，出现「令牌说你是 ADMIN，
 * 但数据库里已经没有 ADMIN 了」的悬空状态。要改名就新建角色再迁移，
 * 这比悄悄改掉一个被引用的标识安全得多。</p>
 */
public record RoleDTO(

        @NotBlank(message = "角色编码不能为空")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$",
                message = "角色编码须以大写字母开头，仅含大写字母/数字/下划线，长度 2-32")
        String code,

        @NotBlank(message = "角色名称不能为空")
        @Size(max = 64, message = "角色名称不能超过 64 个字符")
        String name,

        @Size(max = 255, message = "角色描述不能超过 255 个字符")
        String description,

        @NotNull(message = "状态不能为空")
        @Min(value = 0, message = "状态只能是 0(禁用) 或 1(正常)")
        @Max(value = 1, message = "状态只能是 0(禁用) 或 1(正常)")
        Integer status,

        /** 排序值，可为空，默认 0 */
        @Min(value = 0, message = "排序值不能为负")
        Integer sort
) {
}
