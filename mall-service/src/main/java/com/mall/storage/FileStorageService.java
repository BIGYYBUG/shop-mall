package com.mall.storage;

/**
 * 对象存储抽象。
 *
 * <p><b>为什么要抽这一层</b>：业务代码只需要「把字节存起来，拿到一个 key」，
 * 不需要知道背后是本地磁盘还是阿里云 OSS。有了这层抽象：</p>
 * <ul>
 *   <li>本地开发用 {@code local} 实现，零账号零配置就能跑通全流程；</li>
 *   <li>上线切 {@code oss} 实现，业务代码一行不改，只改 yml 里的一个开关；</li>
 *   <li>将来要换腾讯云 COS / MinIO，加的也只是一个新实现类。</li>
 * </ul>
 *
 * <p>这与 {@code PermissionChecker} 的思路一致：<b>把会变的东西挡在接口后面</b>。</p>
 */
public interface FileStorageService {

    /**
     * 上传一个文件。
     *
     * @param content          文件字节。这里刻意收 byte[] 而不是 MultipartFile，
     *                         是为了让本接口不依赖 spring-web —— 将来在定时任务、
     *                         消息消费等没有 HTTP 请求的场合也能复用
     * @param originalFilename 原始文件名，只用来取扩展名
     * @param contentType      MIME 类型，如 image/jpeg
     * @param category        业务分类，作为对象键的一级目录，如 product / avatar
     * @return 上传结果（objectKey + 可访问地址）
     */
    StoredFile upload(byte[] content, String originalFilename, String contentType, String category);

    /**
     * 删除一个文件。
     *
     * <p>实现应做到「文件不存在也不报错」—— 保证调用方可以安全地重试。</p>
     *
     * @param objectKey 上传时返回的对象键
     */
    void delete(String objectKey);

    /**
     * 把对象键拼成可访问的完整地址。
     *
     * @param objectKey 对象键；为 null 或空白时返回 null
     * @return 完整 URL
     */
    String toAccessUrl(String objectKey);
}
