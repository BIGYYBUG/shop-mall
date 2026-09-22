package com.mall.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 给角色分配权限参数。
 *
 * <p>与 {@link AssignRoleDTO} 同样是<b>全量覆盖</b>语义：提交什么就是最终结果，
 * 空数组 {@code []} 表示清空该角色的全部权限。全量覆盖天然幂等，
 * 重复提交不会产生重复的关联行。</p>
 *
 * <p><b>权限校验的边界</b>：本参数只做「非空校验」，不做「这些权限是否存在」的判断 ——
 * 后者属于业务规则，放在 Service 层（需要查库），放在 DTO 里会变成
 * 一个必须注入 Mapper 的、无法单测的校验注解。</p>
 */
public record AssignPermissionDTO(

        @NotNull(message = "权限列表不能为 null，清空权限请传空数组")
        List<Long> permissionIds
) {
}
