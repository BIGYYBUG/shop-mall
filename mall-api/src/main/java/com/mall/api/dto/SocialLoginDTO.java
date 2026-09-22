package com.mall.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 第三方登录参数。
 *
 * <p>前端从回调地址的 query 里拿到 {@code code}，原样提交给后端。
 * 后端再拿这个 code 去换 access_token —— 这是 OAuth2 授权码模式的关键：
 * <b>access_token 永远只在后端流转，绝不下发到浏览器</b>。</p>
 *
 * @param code  微信返回的一次性授权码，有效期 5 分钟且只能用一次
 * @param state 前端发起授权时传入的随机串，用于防 CSRF，会原样返回
 */
public record SocialLoginDTO(

        @NotBlank(message = "授权码不能为空")
        String code,

        String state
) {
}
