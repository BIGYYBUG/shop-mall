package com.mall.common.config;

import com.mall.common.interceptor.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：注册 JWT 拦截器并声明放行路径。
 *
 * <p><b>放行清单的维护原则</b>：只有「本来就没有令牌可用」的接口才该放行。
 * 每新增一个放行路径，等于在认证墙上开一个洞，必须问一句：这里被刷会不会出事？</p>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                // 默认拦截所有请求
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // 登录、注册：用户此时还没有令牌
                        "/user/login",
                        "/user/register",
                        // 第三方登录：获取授权地址、用 code 换令牌，同样发生在登录之前
                        "/auth/**",
                        // 前台商品浏览：商品列表与详情是电商的默认能力，未登录必须能看。
                        // 写操作全在 /admin/product/** 下，那样才有权限校验。
                        "/product/**",
                        // 本地存储的图片访问路径（mall.storage.type=local 时由
                        // LocalStorageWebConfig 映射）。不放行的话 <img> 标签会拿到 401，
                        // 页面上的图全是破图，而且现象很隐蔽 —— 接口本身是通的。
                        "/uploads/**",
                        // Spring Boot 的错误转发端点，不放行会把 404 变成 401
                        "/error"
                );
    }
}
