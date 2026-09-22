package com.mall.storage.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.mall.common.exception.BusinessException;
import com.mall.storage.FileStorageService;
import com.mall.storage.ObjectKeys;
import com.mall.storage.StorageProperties;
import com.mall.storage.StoredFile;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;

/**
 * 阿里云 OSS 对象存储实现。配置 {@code mall.storage.type=oss} 时生效。
 *
 * <p><b>三个必须理解的 OSS 概念</b>：</p>
 * <ol>
 *   <li><b>Bucket</b> —— 存储空间，名字全局唯一，绑定了地域（Region）。上传时必须
 *       用与 Bucket 同地域的 endpoint，否则会走公网、慢且可能额外计费。</li>
 *   <li><b>Object / objectKey</b> —— Bucket 内的文件，key 就是它在 Bucket 里的完整路径。
 *       OSS 没有真正的「目录」，{@code product/2026/09/19/xxx.jpg} 里那个斜杠
 *       只是 key 的一部分，控制台按斜杠渲染成树而已。</li>
 *   <li><b>Endpoint</b> —— 访问域名。公网 {@code oss-cn-shenzhen.aliyuncs.com}，
 *       内网 {@code oss-cn-shenzhen-internal.aliyuncs.com}。
 *       <b>部署在 ECS 上一定要用内网 endpoint，流量费为 0 且延迟更低。</b></li>
 * </ol>
 *
 * <p><b>关于 OSSClient 的生命周期</b>：它是线程安全的，内部维护 HTTP 连接池，
 * 官方明确要求<b>全应用共用一个实例</b>，不要每次上传都 new 一个 ——
 * 那样每次都要重新建连接池，高并发下会迅速打满 socket。这里在 {@link PostConstruct}
 * 建、{@link PreDestroy} 关，正是这个原因。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.storage.type", havingValue = "oss")
public class AliyunOssFileStorageService implements FileStorageService {

    private final StorageProperties properties;

    private OSS ossClient;

    @PostConstruct
    public void init() {
        StorageProperties.Oss oss = properties.getOss();
        if (!StringUtils.hasText(oss.getEndpoint())
                || !StringUtils.hasText(oss.getBucket())
                || !StringUtils.hasText(oss.getAccessKeyId())
                || !StringUtils.hasText(oss.getAccessKeySecret())) {
            // 快速失败：宁可启动不起来，也不要等到用户上传时才在运行时抛异常。
            // 存储配置错误属于"部署期就能发现"的问题，不该拖到线上。
            throw new IllegalStateException(
                    "已启用 OSS 存储（mall.storage.type=oss），但 endpoint / bucket / access-key-id / access-key-secret 未配置完整");
        }

        this.ossClient = new OSSClientBuilder()
                .build(oss.getEndpoint(), oss.getAccessKeyId(), oss.getAccessKeySecret());
        log.info("阿里云 OSS 客户端已初始化：bucket={}, endpoint={}", oss.getBucket(), oss.getEndpoint());
    }

    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
            log.info("阿里云 OSS 客户端已关闭");
        }
    }

    @Override
    public StoredFile upload(byte[] content, String originalFilename, String contentType, String category) {
        String objectKey = withDir(ObjectKeys.build(category, originalFilename));

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        if (StringUtils.hasText(contentType)) {
            metadata.setContentType(contentType);
        }
        // 让浏览器直接内联预览而不是弹下载框（商品图是要显示的，不是给用户下载的）
        metadata.setContentDisposition("inline");

        try (ByteArrayInputStream in = new ByteArrayInputStream(content)) {
            ossClient.putObject(new PutObjectRequest(
                    properties.getOss().getBucket(), objectKey, in, metadata));
        } catch (Exception e) {
            // 注意：这里抛出的 message 会被 GlobalExceptionHandler 直接返回给前端，
            // 所以不能把 OSS 的原始报错文本透出去 —— 里面可能带有 endpoint、bucket 等内部信息
            log.error("OSS 上传失败：bucket={}, key={}", properties.getOss().getBucket(), objectKey, e);
            throw new BusinessException(500, "文件上传失败，请稍后重试");
        }

        log.info("文件已上传到 OSS：{}（{} 字节）", objectKey, content.length);
        return new StoredFile(objectKey, toAccessUrl(objectKey));
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        String fullKey = withDir(objectKey);
        try {
            ossClient.deleteObject(properties.getOss().getBucket(), fullKey);
            log.info("OSS 对象已删除：{}", fullKey);
        } catch (Exception e) {
            // 删除失败不回滚业务事务：业务数据已经改完了，为了一张图让整个操作失败不值得。
            // 漏删的对象交给 Bucket 生命周期规则或定期清理任务兜底。
            log.warn("OSS 对象删除失败：{}", fullKey, e);
        }
    }

    @Override
    public String toAccessUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        StorageProperties.Oss oss = properties.getOss();
        String prefix = StringUtils.hasText(oss.getUrlPrefix())
                ? oss.getUrlPrefix()
                : "https://" + oss.getBucket() + "." + oss.getEndpoint();
        return (prefix.endsWith("/") ? prefix : prefix + "/") + withDir(objectKey);
    }

    /**
     * 把配置里的统一目录前缀拼到 key 前面。
     *
     * <p>做幂等处理：库里存的 key 可能已经带过前缀（历史数据或人工录入），
     * 重复拼接会得到 {@code mall/mall/product/...} 这种错误路径，
     * 而且不报错、只是 404，非常难查。</p>
     */
    private String withDir(String objectKey) {
        String dir = properties.getOss().getDir();
        if (!StringUtils.hasText(dir)) {
            return objectKey;
        }
        String normalized = dir.endsWith("/") ? dir : dir + "/";
        return objectKey.startsWith(normalized) ? objectKey : normalized + objectKey;
    }
}
