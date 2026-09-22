package com.mall.social;

/**
 * 第三方平台返回的用户信息（渠道无关的统一模型）。
 *
 * <p>各平台返回的字段名、结构完全不同（微信叫 openid/headimgurl，GitHub 叫 id/avatar_url）。
 * 让 {@link SocialLoginProvider} 的实现负责「翻译」成这个统一模型，
 * 上层的登录逻辑就只需要处理一种结构 —— 将来接入 QQ、支付宝登录时，
 * 加一个 Provider 即可，登录主流程一行都不用改。</p>
 *
 * @param openid   渠道内唯一标识（必填）。微信场景下即 openid
 * @param unionid  跨应用统一标识（可空）。只有绑定到开放平台账号才有
 * @param nickname 昵称（可空）
 * @param avatar   头像 URL（可空）
 * @param source   渠道标识，如 wechat
 */
public record SocialUserInfo(
        String openid,
        String unionid,
        String nickname,
        String avatar,
        String source
) {
}
