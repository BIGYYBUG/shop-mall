package com.mall.storage.local;

import com.mall.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地存储的静态资源映射：把 {@code /uploads/**} 映射到磁盘目录。
 *
 * <p><b>为什么本地实现需要它，OSS 实现不需要</b>：OSS 的图片由阿里云的域名直接对外提供，
 * 浏览器拿到的 URL 指向 OSS，根本不经过我们的应用。本地存储没有这层外部服务，
 * 只能让 Spring MVC 自己把文件读出来返回。</p>
 *
 * <p><b>它为什么不在 mall-common 的 WebMvcConfig 里</b>：那样 common 就得知道
 * 「本地存储的目录在哪」这个 service 层概念，依赖方向就反了
 * （Maven 方向是 service → common，common 不能反向依赖）。
 * 这里用「多个 WebMvcConfigurer」的方式各管各的，互不干扰。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageWebConfig implements WebMvcConfigurer {

    private final StorageProperties properties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String urlPrefix = properties.getLocal().getUrlPrefix();
        Path baseDir = Paths.get(properties.getLocal().getBaseDir()).toAbsolutePath().normalize();

        // "/uploads/" -> "/uploads/**"；结尾必须带 /，否则 Spring 会拼成非法路径
        String pattern = urlPrefix.endsWith("/") ? urlPrefix + "**" : urlPrefix + "/**";
        String location = baseDir.toUri().toString();

        registry.addResourceHandler(pattern).addResourceLocations(location);
        log.info("本地文件访问已开启：{} -> {}", pattern, location);
    }
}
