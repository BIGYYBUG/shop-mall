package com.mall.social;

/**
 * 第三方登录渠道的统一抽象。
 *
 * <p>OAuth2 授权码模式分两步，接口也相应定义两个方法：</p>
 * <ol>
 *   <li>{@link #buildAuthorizeUrl(String)}：前端拿到地址后跳转 → 用户扫码授权 →
 *       渠道方回调到前端并带上 {@code code}</li>
 *   <li>{@link #getUserInfo(String)}：后端用 {@code code} 换取 access_token，
 *       再拉取用户信息</li>
 * </ol>
 *
 * <p><b>实现约定</b>：新增一个渠道只要实现本接口并注册为 Spring Bean，
 * {@code SocialLoginServiceImpl} 会自动把它纳入可选范围（靠构造器注入 List）。</p>
 */
public interface SocialLoginProvider {

    /**
     * 渠道标识，需与前端路由中的 {source} 一致，如 wechat
     */
    String source();

    /**
     * 生成前端跳转用的授权地址
     *
     * @param state 防 CSRF 的随机串，渠道方会原样回传
     * @return 完整授权 URL
     */
    String buildAuthorizeUrl(String state);

    /**
     * 用授权码换取用户信息
     *
     * @param code 渠道方回传的一次性授权码
     * @return 统一模型下的用户信息
     */
    SocialUserInfo getUserInfo(String code);

    /**
     * 渠道是否已完成配置（appId / appSecret 是否填写）。
     *
     * <p>用于前端判断要不要显示「微信登录」按钮 —— 没配置就藏起来，
     * 比让用户点了才报错体验好得多。</p>
     */
    boolean configured();
}
