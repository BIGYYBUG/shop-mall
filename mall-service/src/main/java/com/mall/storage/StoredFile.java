package com.mall.storage;

/**
 * 一次成功上传的结果。
 *
 * <p>两个字段各有用途，缺一不可：</p>
 * <ul>
 *   <li>{@code objectKey} —— 要落库的值。业务表（如 mall_product.cover_key）存的是它。</li>
 *   <li>{@code url} —— 给前端立即预览用的完整地址。上传统完立刻能看到图，
 *       不用等业务接口返回后再拼一遍。</li>
 * </ul>
 *
 * @param objectKey 存储层唯一键，如 {@code product/2026/09/19/8f3a....jpg}
 * @param url       可直接访问的完整地址
 */
public record StoredFile(String objectKey, String url) {
}
