package com.mall.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理端修改用户信息参数。
 *
 * <p><b>注意这里没有 username、也没有 password。</b>用户名是登录凭据，属于身份标识，
 * 不允许管理端随手改；密码走独立的「重置密码」接口，因为它的校验规则和审计要求都不同。
 * 一个 DTO 管一件事，能避免「改昵称顺手把密码也带上了」这类事故。</p>
 *
 * <p>正则说明：{@code ^$|^1[3-9]\d{9}$} 里的 {@code ^$} 是「允许留空」。
 * 手机号不是必填项，如果不写 {@code ^$}，前端提交空字符串会被 @Pattern 拦下。</p>
 */
public record AdminUserUpdateDTO(

        @Size(max = 64, message = "昵称长度不能超过 64")
        String nickname,

        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱长度不能超过 128")
        String email,

        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @Size(max = 256, message = "头像地址过长")
        String avatar
) {
}
