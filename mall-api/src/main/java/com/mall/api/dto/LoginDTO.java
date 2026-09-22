package com.mall.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求参数
 */
public record LoginDTO(
        @NotBlank(message = "用户名不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        String password
) {
}
