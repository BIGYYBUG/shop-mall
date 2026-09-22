package com.mall.social.wechat;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mall.common.exception.BusinessException;
import com.mall.social.SocialLoginProvider;
import com.mall.social.SocialUserInfo;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;

/**
 * 微信开放平台「网站应用」扫码登录实现。
 *
 * <h3>完整链路（这就是 OAuth2 授权码模式）</h3>
 * <pre>
 *  浏览器                前端              mall-web            微信服务器
 *    │  点「微信登录」     │                   │                   │
 *    │────────────────────►│  GET /auth/wechat/authorize-url        │
 *    │                     │──────────────────►│                   │
 *    │                     │◄──────────────────│ 返回授权 URL      │
 *    │  跳转授权 URL        │                   │                   │
 *    │─────────────────────────────────────────────────────────────►│
 *    │  扫码确认            │                   │                   │
 *    │◄─────────────────────────────────────────────────────────────│
 *    │  回调 http://前端/oauth/callback?code=XXX&state=YYY          │
 *    │                     │  POST /auth/wechat/login {code}       │
 *    │                     │──────────────────►│                   │
 *    │                     │                   │ code+secret 换 token
 *    │                     │                   │──────────────────►│
 *    │                     │                   │◄──────────────────│
 *    │                     │                   │ 拉取用户信息       │
 *    │                     │                   │──────────────────►│
 *    │                     │                   │◄──────────────────│
 *    │                     │◄──────────────────│ 签发本项目 JWT     │
 *    │◄────────────────────│   登录成功         │                   │
 * </pre>
 *
 * <h3>三个必须知道的坑</h3>
 * <ol>
 *   <li><b>access_token 绝不能下发到浏览器</b>。它就是「以你的名义调用微信接口」的通行证，
 *       所以「code 换 token」这一步必须在后端完成 —— 这正是授权码模式存在的意义</li>
 *   <li><b>code 只能用一次</b>，有效期 5 分钟。前端刷新页面重复提交会报 40163（code 已被使用），
 *       这是正常现象，需要用户重新授权</li>
 *   <li><b>微信的 openid 是「用户 + 应用」维度的</b>。同一个微信用户在你的网站应用和公众号下
 *       openid 不同。要跨应用认人，必须用 unionid（前提是应用都绑定到同一个开放平台账号）</li>
 * </ol>
 */
@Slf4j
@Component
public class WechatSocialLoginProvider implements SocialLoginProvider {

    /** 网站应用扫码登录授权页（注意域名是 open.weixin.qq.com，不是 api.weixin.qq.com） */
    private static final String AUTHORIZE_URL = "https://open.weixin.qq.com/connect/qrconnect";

    /** 用 code 换 access_token */
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";

    /** 拉取用户信息 */
    private static final String USER_INFO_URL = "https://api.weixin.qq.com/sns/userinfo";

    private final WechatProperties properties;

    private final RestClient restClient;

    public WechatSocialLoginProvider(WechatProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        // 统一设置超时：第三方接口不可控，不设超时会把本服务的线程池拖死
        this.restClient = restClientBuilder
                .requestFactory(buildRequestFactory())
                .build();
    }

    /**
     * 构造带超时的请求工厂。
     *
     * <p>连接超时 3 秒、读取超时 5 秒，都是按「用户可接受的最长等待」倒推的。
     * 第三方接口的默认超时是「无限等待」，一旦微信侧卡住，Tomcat 线程会一个个被占满，
     * 最终整个服务不可用 —— 这是典型的「被下游拖死」。</p>
     */
    private static SimpleClientHttpRequestFactory buildRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return factory;
    }

    @Override
    public String source() {
        return "wechat";
    }

    @Override
    public boolean configured() {
        return properties.configured();
    }

    @Override
    public String buildAuthorizeUrl(String state) {
        requireConfigured();
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("appid", properties.getAppId())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", properties.getScope())
                .queryParam("state", state)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString()
                // 末尾锚点由微信要求固定拼接，不能参与 URL 编码
                + "#wechat_redirect";
    }

    @Override
    public SocialUserInfo getUserInfo(String code) {
        requireConfigured();

        // ---------- 第一步：code 换 access_token ----------
        WechatTokenResponse token = restClient.get()
                .uri(ACCESS_TOKEN_URL
                                + "?appid={appid}&secret={secret}&code={code}&grant_type=authorization_code",
                        properties.getAppId(), properties.getAppSecret(), code)
                .retrieve()
                .body(WechatTokenResponse.class);

        if (token == null) {
            throw new BusinessException(502, "微信授权接口无响应，请稍后重试");
        }
        // 微信的成功响应里没有 errcode，有 errcode 就一定是失败
        if (token.getErrcode() != null && token.getErrcode() != 0) {
            log.warn("微信 code 换 token 失败：errcode={}, errmsg={}", token.getErrcode(), token.getErrmsg());
            throw new BusinessException(400, "微信授权失败：" + describeWechatError(token.getErrcode(), token.getErrmsg()));
        }
        if (!StringUtils.hasText(token.getOpenid())) {
            throw new BusinessException(502, "微信返回的数据不完整（缺少 openid）");
        }

        // ---------- 第二步：拉取用户资料 ----------
        WechatUserResponse profile = restClient.get()
                .uri(USER_INFO_URL + "?access_token={token}&openid={openid}&lang=zh_CN",
                        token.getAccessToken(), token.getOpenid())
                .retrieve()
                .body(WechatUserResponse.class);

        if (profile == null || !StringUtils.hasText(profile.getOpenid())) {
            // 拉资料失败不算致命：openid 已经拿到了，用户可以登录，只是没昵称头像
            log.warn("微信用户资料拉取失败，仅使用 openid 登录：openid={}", token.getOpenid());
            return new SocialUserInfo(token.getOpenid(), token.getUnionid(), null, null, source());
        }

        // unionid 优先级：用户资料接口里的更准，缺失时回落到 token 接口的
        String unionid = StringUtils.hasText(profile.getUnionid())
                ? profile.getUnionid() : token.getUnionid();

        return new SocialUserInfo(
                profile.getOpenid(),
                unionid,
                StringUtils.hasText(profile.getNickname()) ? profile.getNickname() : null,
                StringUtils.hasText(profile.getHeadimgurl()) ? profile.getHeadimgurl() : null,
                source());
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new BusinessException(500,
                    "微信登录尚未配置：请在 application.yml 中填写 mall.wechat.app-id 与 app-secret");
        }
    }

    /**
     * 把微信的错误码翻译成人话。这些是接入时最常撞上的几个。
     */
    private String describeWechatError(Integer errcode, String errmsg) {
        if (errcode == null) {
            return errmsg;
        }
        return switch (errcode) {
            case 40029 -> "授权码无效或已被使用，请重新扫码";
            case 40163 -> "授权码已被使用，请重新扫码";
            case 40013 -> "AppID 配置有误";
            case 40125 -> "AppSecret 配置有误";
            case 41008 -> "缺少 code 参数";
            default -> errmsg == null ? ("错误码 " + errcode) : errmsg;
        };
    }

    // ==================================================================
    // 微信接口的响应结构。字段名是下划线风格，必须用 @JsonProperty 映射
    // ==================================================================

    @Data
    public static class WechatTokenResponse {

        @JsonProperty("access_token")
        private String accessToken;

        @JsonProperty("expires_in")
        private Integer expiresIn;

        @JsonProperty("refresh_token")
        private String refreshToken;

        @JsonProperty("openid")
        private String openid;

        @JsonProperty("scope")
        private String scope;

        @JsonProperty("unionid")
        private String unionid;

        @JsonProperty("errcode")
        private Integer errcode;

        @JsonProperty("errmsg")
        private String errmsg;
    }

    @Data
    public static class WechatUserResponse {

        @JsonProperty("openid")
        private String openid;

        @JsonProperty("nickname")
        private String nickname;

        @JsonProperty("sex")
        private Integer sex;

        @JsonProperty("province")
        private String province;

        @JsonProperty("city")
        private String city;

        @JsonProperty("country")
        private String country;

        @JsonProperty("headimgurl")
        private String headimgurl;

        @JsonProperty("unionid")
        private String unionid;

        @JsonProperty("errcode")
        private Integer errcode;

        @JsonProperty("errmsg")
        private String errmsg;
    }
}
