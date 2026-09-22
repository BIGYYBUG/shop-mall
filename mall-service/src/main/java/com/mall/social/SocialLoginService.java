package com.mall.social;

import com.mall.api.vo.LoginVO;

import java.util.List;

/**
 * 第三方登录服务。
 */
public interface SocialLoginService {

    /**
     * 列出已完成配置（可用）的渠道标识，供前端决定显示哪些登录按钮
     */
    List<String> listAvailableSources();

    /**
     * 生成指定渠道的授权跳转地址
     *
     * @param source 渠道标识，如 wechat
     * @param state  防 CSRF 随机串
     */
    String buildAuthorizeUrl(String source, String state);

    /**
     * 用授权码完成登录：查得到就登录，查不到就自动注册后登录
     *
     * @param source 渠道标识
     * @param code   渠道方回传的授权码
     * @return 与本项目账号体系一致的令牌 + 用户信息
     */
    LoginVO login(String source, String code);
}
