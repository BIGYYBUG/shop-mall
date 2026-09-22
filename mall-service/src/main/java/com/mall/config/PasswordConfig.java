package com.mall.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码加密器配置。
 *
 * <p>BCrypt 的两个关键特性：</p>
 * <ol>
 *   <li><b>单向</b>：只能加密不能解密，所以数据库泄露也拿不到明文密码</li>
 *   <li><b>自带随机盐</b>：同一个密码每次加密结果都不同，杜绝彩虹表攻击</li>
 * </ol>
 *
 * <p>校验密码用 {@code encoder.matches(明文, 哈希)}，不要再加密一遍去比字符串。</p>
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
