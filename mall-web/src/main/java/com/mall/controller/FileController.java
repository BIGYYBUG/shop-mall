package com.mall.controller;

import com.mall.common.annotation.RequiresPermission;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.Result;
import com.mall.storage.FileStorageService;
import com.mall.storage.StoredFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 文件上传。
 *
 * <p><b>当前是「后端中转」模式</b>：浏览器 → 本应用 → OSS。
 * 好处是简单、能直接做校验；代价是文件字节要过一次应用服务器的带宽和堆内存，
 * 图片一多就会成为瓶颈与成本。</p>
 *
 * <p>生产环境的正确形态是<b>前端直传 OSS</b>：本应用只负责签发一个
 * 有时效、有目录限制的凭证（STS 临时凭证或 PostObject 签名），
 * 浏览器拿着凭证直接把文件 PUT 到 OSS，字节完全不经过应用服务器。
 * 具体方案见 {@code docs/oss-integration-guide.md}。</p>
 */
@Slf4j
@RestController
@RequestMapping("/file")
@RequiredArgsConstructor
public class FileController {

    /** 单张图片上限 5MB。与 yml 里的 spring.servlet.multipart.max-file-size 保持一致 */
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private final FileStorageService fileStorageService;

    /**
     * 上传图片。
     *
     * @param file     上传的文件
     * @param category 业务分类，作为对象键的一级目录，如 product / avatar
     * @return objectKey（落库用）+ url（前端预览用）
     */
    @PostMapping("/upload")
    @RequiresPermission("file:upload")
    public Result<StoredFile> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam(defaultValue = "common") String category) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件不能为空");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new BusinessException(400, "图片不能超过 5MB");
        }

        byte[] content = file.getBytes();

        // 校验分三层，缺一层就能被绕过：
        //   ① 扩展名白名单（在 ObjectKeys 里）
        //   ② Content-Type 声明
        //   ③ 文件头魔数 —— 唯一伪造不了的一层
        // 只看扩展名的话，把 shell.php 改名成 shell.jpg 就能传上来；
        // 只看 Content-Type 的话，改个请求头即可。必须看真实字节。
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new BusinessException(400, "只允许上传图片文件");
        }
        if (!isRealImage(content)) {
            throw new BusinessException(400, "文件内容不是有效的图片");
        }

        StoredFile stored = fileStorageService.upload(content, file.getOriginalFilename(), contentType, category);
        log.info("图片上传成功：key={}, size={}", stored.objectKey(), content.length);
        return Result.success("上传成功", stored);
    }

    /**
     * 通过文件头魔数判断是不是真实图片。
     *
     * <p>各类格式的固定开头（十六进制）：JPEG {@code FF D8 FF}、
     * PNG {@code 89 50 4E 47}、GIF {@code 47 49 46 38}、BMP {@code 42 4D}。
     * WEBP 和 AVIF 属于容器格式，魔数不在第 0 字节，要按偏移量取。</p>
     */
    private boolean isRealImage(byte[] data) {
        if (data.length < 12) {
            return false;
        }
        // JPEG
        if (match(data, 0, 0xFF, 0xD8, 0xFF)) {
            return true;
        }
        // PNG
        if (match(data, 0, 0x89, 0x50, 0x4E, 0x47)) {
            return true;
        }
        // GIF8
        if (match(data, 0, 0x47, 0x49, 0x46, 0x38)) {
            return true;
        }
        // BMP
        if (match(data, 0, 0x42, 0x4D)) {
            return true;
        }
        // WEBP：第 0-3 字节是 "RIFF"，第 8-11 字节是 "WEBP"
        if (match(data, 0, 0x52, 0x49, 0x46, 0x46) && match(data, 8, 0x57, 0x45, 0x42, 0x50)) {
            return true;
        }
        // AVIF：第 4-11 字节是 "ftypavif" 或 "ftypavis"
        return "ftypavif".equals(new String(data, 4, 8, StandardCharsets.US_ASCII))
                || "ftypavis".equals(new String(data, 4, 8, StandardCharsets.US_ASCII));
    }

    /** 从 offset 起逐字节比对期望值 */
    private boolean match(byte[] data, int offset, int... expected) {
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((data[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
