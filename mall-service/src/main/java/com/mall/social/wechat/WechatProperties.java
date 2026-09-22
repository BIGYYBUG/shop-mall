package com.mall.social.wechat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 微信开放平台配置。
 *
 * <p>用 {@code @ConfigurationProperties} 而不是在代码里散落 {@code @Value}，
 * 好处有三：</p>
 * <ol>
 *   <li>类型安全：配置项有 IDE 提示，写错名字启动就能发现</li>
 *   <li>集中管理：一组相关配置收在一个类里，一眼看清有哪些开关</li>
 *   <li>可测试：单元测试直接 new 出来塞值，不用起 Spring 容器</li>
 * </ol>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.wechat")
public class WechatProperties {

    /** 开放平台网站应用 AppID */
    private String appId;

    /** 开放平台网站应用 AppSecret */
    private String appSecret;

    /** 授权后回调的前端地址，必须与开放平台后台登记的授权回调域一致 */
    private String redirectUri;

    /** 授权作用域，网站应用扫码登录固定为 snsapi_login */
    private String scope = "snsapi_login";

    /**
     * 是否已完整配置。
     *
     * <p>注意 AppSecret 只能放服务端，绝不能出现在前端代码或令牌里。</p>
     */
    public boolean configured() {
        return StringUtils.hasText(appId) && StringUtils.hasText(appSecret);
    }
}
