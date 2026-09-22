package com.mall.api.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 登录成功返回：令牌 + 用户信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** JWT 令牌，前端需保存并放进后续请求的 Authorization 头 */
    private String token;

    /** 用户基本信息 */
    private UserVO userInfo;
}
