package com.mall.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 店铺审核参数。
 *
 * <p>审核结果只能是「通过(1)」「驳回(2)」「冻结(3)」，所以 {@code @Min(1)} 把
 * 0（待审核）挡在契约之外 —— 审核动作的意义就是离开"待审核"这个状态，
 * 允许传 0 只会制造无意义的调用。</p>
 *
 * <p>{@code rejectReason} 只在驳回时有意义，但它的"必填性"依赖 {@code status} 的值，
 * 属于跨字段规则，{@code @NotNull} 这类单字段注解表达不了 —— 放在服务层校验。</p>
 */
public record ShopAuditDTO(

        @NotNull(message = "审核结果不能为空")
        @Min(value = 1, message = "审核结果只能是 1(通过) / 2(驳回) / 3(冻结)")
        @Max(value = 3, message = "审核结果只能是 1(通过) / 2(驳回) / 3(冻结)")
        Integer status,

        @Size(max = 255, message = "驳回原因不能超过 255 个字符")
        String rejectReason
) {
}
