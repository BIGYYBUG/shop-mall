package com.mall.common.util;

/**
 * 字符串归一化工具。
 *
 * <p><b>为什么需要它</b>：前端清空输入框后提交的是空串 {@code ""}，而不是 {@code null}。
 * 如果直接入库，表里就会堆积大量「空串」值 —— 它们既不等于 {@code null}，
 * 所以 {@code WHERE email IS NULL} 查不到、{@code WHERE email = ''} 又容易漏，
 * 是后期做数据清洗时最头疼的一类脏数据。</p>
 *
 * <p>统一在写库前把空白归一成 {@code null}，让「没有值」在数据库里只有一种表示。</p>
 */
public final class TextUtils {

    private TextUtils() {
        // 工具类不允许实例化
    }

    /**
     * 去除首尾空白；若结果为空则返回 {@code null}。
     *
     * <pre>
     *   trimToNull(null)    → null
     *   trimToNull("")      → null
     *   trimToNull("   ")   → null
     *   trimToNull("  a ")  → "a"
     * </pre>
     *
     * @param value 原始字符串，可为 null
     * @return 去空白后的字符串，或 null
     */
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
