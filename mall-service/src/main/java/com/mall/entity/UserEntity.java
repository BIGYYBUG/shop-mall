package com.mall.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户实体，对应表 mall_user。
 *
 * <p>继承 BaseEntity 拿到 id / createTime / updateTime / deleted 四个通用字段。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_user")
public class UserEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 登录名。第三方登录用户形如 wx_xxxxxxxx */
    private String username;

    /** 密码哈希（BCrypt），绝不存明文 */
    private String password;

    /** 手机号 */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 昵称 */
    private String nickname;

    /** 头像 URL */
    private String avatar;

    /**
     * 微信开放平台唯一标识（用户 + 应用维度）。
     *
     * <p>同一个用户在公众号、小程序、网站应用下 openid 各不相同，
     * 所以它只能标识「这个应用的这个人」，不能跨应用识别。</p>
     */
    private String openid;

    /**
     * 微信开放平台统一标识（用户 + 开放平台账号维度）。
     *
     * <p>同一个微信开放平台账号下的多个应用，unionid 是同一个值，
     * 这才是跨应用打通账号的正确依据。所以 openid 用于「查得到」，
     * unionid 用于「将来能合并」，两个都要存。</p>
     */
    private String unionid;

    /** 状态：0 禁用，1 正常 */
    private Integer status;
}
