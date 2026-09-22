package com.mall.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.mall.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品实体，对应表 mall_product。
 *
 * <p><b>为什么是单表</b>：当前没有多规格（颜色 / 尺码）需求，直接一张表打直球。
 * SPU + SKU 双层至少要再加三张表，且几乎一半的查询都要 join，
 * 属于「现在不需要、将来才可能需要」的复杂度。</p>
 *
 * <p><b>图片字段为什么叫 XXXKey 而不是 XXXUrl</b>：库里存的是 OSS 对象键
 * （如 {@code product/2026/09/a1b2c3.jpg}），不是完整 URL。出参时再由
 * {@code FileStorageService} 拼装成可访问地址。这样换 bucket、换 CDN 域名时
 * 不需要动数据库里任何一行。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
// autoResultMap = true 是必需的，见下方 images 字段的说明
@TableName(value = "mall_product", autoResultMap = true)
public class ProductEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 商品名称 */
    private String name;

    /** 副标题 / 卖点，列表页展示用 */
    private String subtitle;

    /** 分类 ID。分类表还没建，先保留字段 */
    private Long categoryId;

    /**
     * 卖家用户 ID。<b>0 表示平台自营</b>。
     *
     * <p><b>为什么用 0 而不是 NULL</b>：平台自营是一种确定的归属，不是"未知"。
     * 若存 NULL，卖家侧所有查询都要写成 {@code seller_id = ?}，而 NULL 与任何值
     * 比较结果都是 NULL，永远不命中 —— 于是必须额外补 {@code OR seller_id IS NULL}，
     * 既容易漏写，也会让 {@code idx_seller_status_sort} 索引失效。</p>
     *
     * <p><b>它同时是行级权限的锚点</b>：卖家能改哪些商品，完全由这个字段决定
     * （{@code WHERE seller_id = 当前用户}）。RBAC 的接口级权限只回答
     * 「能不能调这个接口」，回答不了「能操作哪几行数据」，两者缺一不可。</p>
     */
    private Long sellerId;

    /** 售价 */
    private BigDecimal price;

    /** 原价 / 划线价 */
    private BigDecimal originalPrice;

    /** 库存数量 */
    private Integer stock;

    /** 累计销量 */
    private Integer sales;

    /** 封面图 objectKey */
    private String coverKey;

    /**
     * 商品图集 objectKey 列表。
     *
     * <p><b>这里有一个 MyBatis-Plus 的经典坑</b>：{@code @TableField(typeHandler = ...)}
     * 只对「写」方向保证生效；「读」方向（把 JSON 列反序列化成 List）必须同时
     * 在 {@code @TableName} 上打开 {@code autoResultMap = true}。只加 typeHandler
     * 不加 autoResultMap 时，查询不会报错，但 images 永远是 null ——
     * 因为 MyBatis 用的是自动生成的 ResultMap，根本没带上这个 handler。</p>
     *
     * <p>另一个替代方案是自定义 XML 里手写 {@code <result typeHandler="..."/>}，
     * 但那样每写一处查询就得重复一遍，容易漏。</p>
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> images;

    /** 商品详情（富文本 / Markdown） */
    private String description;

    /** 状态：0 下架，1 上架 */
    private Integer status;

    /** 排序值，越大越靠前 */
    private Integer sort;
}
