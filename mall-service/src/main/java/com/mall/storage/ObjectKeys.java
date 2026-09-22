package com.mall.storage;

import com.mall.common.exception.BusinessException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 对象键（objectKey）生成规则。
 *
 * <p>格式：{@code {category}/{yyyy}/{MM}/{dd}/{uuid}.{ext}}，
 * 例如 {@code product/2026/09/19/8f3a1c2e-....jpg}。</p>
 *
 * <p><b>为什么不用原始文件名</b>：</p>
 * <ul>
 *   <li>原始文件名可能重复，直接覆盖会丢图；</li>
 *   <li>原始文件名可能含中文、空格、{@code ../} —— 既是路径穿越风险，
 *       也会在 URL 里被反复转义；</li>
 *   <li>用户上传的文件名常常泄露隐私（如 {@code 张三身份证.jpg}），
 *       存进对象存储就等于把它公开了。</li>
 * </ul>
 *
 * <p><b>为什么按日期分目录</b>：单目录下对象数超过十万级时，
 * 控制台列举和生命周期规则都会变慢。按天分目录是零成本的预防措施。</p>
 */
public final class ObjectKeys {

    /** 允许的扩展名白名单，一律小写比较 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "avif"
    );

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 分类名只允许小写字母、数字与短横线，防止有人从外部传入 {@code ../} 这类路径 */
    private static final Pattern CATEGORY_PATTERN = Pattern.compile("^[a-z0-9-]{1,32}$");

    private ObjectKeys() {
    }

    /**
     * 生成对象键。
     *
     * @param category         业务分类，如 product
     * @param originalFilename 原始文件名，仅用于提取扩展名，可以为 null
     * @return 形如 {@code product/2026/09/19/{uuid}.jpg}
     */
    public static String build(String category, String originalFilename) {
        String safeCategory = sanitizeCategory(category);
        String extension = extractExtension(originalFilename);
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return safeCategory + "/" + LocalDate.now().format(DATE_PATH) + "/" + uuid + "." + extension;
    }

    private static String sanitizeCategory(String category) {
        String value = (category == null || category.isBlank()) ? "common" : category.trim().toLowerCase(Locale.ROOT);
        if (!CATEGORY_PATTERN.matcher(value).matches()) {
            throw new BusinessException(400, "非法的文件分类：" + category);
        }
        return value;
    }

    private static String extractExtension(String originalFilename) {
        if (originalFilename == null) {
            throw new BusinessException(400, "文件名不能为空");
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            throw new BusinessException(400, "文件缺少扩展名，无法识别格式");
        }
        String extension = originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(400, "不支持的文件格式：" + extension);
        }
        return extension;
    }
}
