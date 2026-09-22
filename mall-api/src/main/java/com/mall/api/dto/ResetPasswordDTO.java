package com.mall.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重置密码参数。
 *
 * <p>不做「原密码校验」：这是管理端接口，操作者本人不是账号所有者，
 * 要他填别人的旧密码既不合理也不可能。权限由 {@code user:password} 控制，
 * 操作留痕由日志承担。</p>
 */
public record ResetPasswordDTO(

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度需在 6-32 之间")
        String newPassword
) {
}
