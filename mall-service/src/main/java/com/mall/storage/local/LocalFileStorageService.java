package com.mall.storage.local;

import com.mall.common.exception.BusinessException;
import com.mall.storage.FileStorageService;
import com.mall.storage.ObjectKeys;
import com.mall.storage.StorageProperties;
import com.mall.storage.StoredFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地磁盘存储实现。
 *
 * <p>默认生效（{@code matchIfMissing = true}）。目的是<b>让项目在没有任何云账号的情况下
 * 也能跑通完整的「上传 → 存 key → 出参拼 URL」链路</b>。本地开发和写单元测试时用它，
 * 没人愿意为了调一个上传接口先去注册阿里云账号。</p>
 *
 * <p>生产环境不要用它：多实例部署时各自读写各自的磁盘，A 实例传的图 B 实例读不到；
 * 容器重启后文件全丢；也没有 CDN 和按量计费。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    private final StorageProperties properties;

    @Override
    public StoredFile upload(byte[] content, String originalFilename, String contentType, String category) {
        String objectKey = ObjectKeys.build(category, originalFilename);
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            log.error("本地文件写入失败：{}", target, e);
            throw new BusinessException(500, "文件保存失败");
        }
        log.info("文件已保存到本地：{}（{} 字节）", target, content.length);
        return new StoredFile(objectKey, toAccessUrl(objectKey));
    }

    @Override
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            // 删不存在的文件不报错，保证调用方可安全重试
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException e) {
            log.warn("本地文件删除失败：{}", objectKey, e);
        }
    }

    @Override
    public String toAccessUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        String prefix = properties.getLocal().getUrlPrefix();
        return prefix.endsWith("/") ? prefix + objectKey : prefix + "/" + objectKey;
    }

    /**
     * 把对象键解析成绝对路径，并校验它没有逃出根目录。
     *
     * <p><b>这一步不能省。</b>若对象键里含 {@code ../../} 而没做校验，
     * 攻击者就能把文件写到项目目录之外（甚至覆盖配置文件）。
     * 虽然 {@link ObjectKeys} 已经从源头限制了字符集，但存储层自己再兜一道底
     * 才是正确的纵深防御姿势 —— 万一将来有人换了别的 key 生成方式呢。</p>
     */
    private Path resolve(String objectKey) {
        Path baseDir = Paths.get(properties.getLocal().getBaseDir()).toAbsolutePath().normalize();
        Path target = baseDir.resolve(objectKey).normalize();
        if (!target.startsWith(baseDir)) {
            throw new BusinessException(400, "非法的文件路径");
        }
        return target;
    }
}
