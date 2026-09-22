package com.mall.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 分配用户角色参数。
 *
 * <p>语义是「全量覆盖」而非「增量追加」：提交什么就是最终结果。
 * 空列表 {@code []} 合法，表示收回该用户全部角色。全量覆盖的好处是
 * 幂等 —— 同一个请求发两次，结果完全一致，不会重复插入关联关系。</p>
 */
public record AssignRoleDTO(

        @NotNull(message = "角色列表不能为 null，清空角色请传空数组")
        List<Long> roleIds
) {
}
