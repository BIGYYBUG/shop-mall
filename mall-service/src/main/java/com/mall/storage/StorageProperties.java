package com.mall.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储配置，对应 yml 里的 {@code mall.storage.*}。
 *
 * <p>{@code type} 是总开关：{@code local} 走本地磁盘，{@code oss} 走阿里云。
 * 两个实现类各自用 {@code @ConditionalOnProperty} 判断自己该不该生效，
 * 因此同一时刻容器里只会存在一个 {@link FileStorageService} Bean，
 * 注入方不需要写任何 if/else。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.storage")
public class StorageProperties {

    /** 存储类型：local（默认）| oss */
    private String type = "local";

    /** 本地存储配置 */
    private Local local = new Local();

    /** 阿里云 OSS 配置 */
    private Oss oss = new Oss();

    @Data
    public static class Local {

        /** 文件落盘根目录 */
        private String baseDir = "uploads";

        /** 对外暴露的 URL 前缀，需与静态资源映射保持一致 */
        private String urlPrefix = "/uploads/";
    }

    @Data
    public static class Oss {

        /**
         * 地域节点，如 {@code oss-cn-shenzhen.aliyuncs.com}。
         *
         * <p>注意：不要写 bucket 名（{@code mybucket.oss-cn-...}），
         * bucket 是独立配置项，SDK 会自己拼。</p>
         */
        private String endpoint;

        /** Bucket 名称 */
        private String bucket;

        /**
         * AccessKey ID。
         *
         * <p><b>务必用 RAM 子账号的 AK，不要用主账号 AK。</b>
         * 主账号 AK 拥有账号下全部云产品的完全控制权，一旦泄露等于账号被接管。
         * 生产环境应通过环境变量注入，绝不要把 AK 写进代码库。</p>
         */
        private String accessKeyId;

        /** AccessKey Secret */
        private String accessKeySecret;

        /** 统一前缀目录，便于按业务隔离与生命周期管理 */
        private String dir = "mall";

        /**
         * 访问域名。留空时自动用 {@code https://{bucket}.{endpoint}} 拼。
         *
         * <p>绑定了 CDN 或自定义域名时填这里，例如 {@code https://img.example.com}。</p>
         */
        private String urlPrefix;
    }
}
