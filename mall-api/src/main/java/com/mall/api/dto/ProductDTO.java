package com.mall.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 商品 DTO：入参对象（新增与修改共用）。
 *
 * <p><b>为什么不让前端直接传 @RequestBody ProductEntity</b>：
 * 实体带着 deleted、sales 这类不该由客户端决定的字段。一旦直接绑实体，
 * 恶意请求就能把 sales 改成 99999、把 deleted 改成 1。
 * DTO 是明确的「允许客户端写哪些字段」的白名单。</p>
 */
@Data
public class ProductDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 新增时忽略；修改时由路径参数决定，不从这里取 */
    private Long id;

    @NotBlank(message = "商品名称不能为空")
    @Size(max = 128, message = "商品名称不能超过 128 个字符")
    private String name;

    @Size(max = 255, message = "副标题不能超过 255 个字符")
    private String subtitle;

    private Long categoryId;

    @NotNull(message = "商品价格不能为空")
    @DecimalMin(value = "0.00", message = "商品价格不能为负")
    @Digits(integer = 8, fraction = 2, message = "商品价格最多 8 位整数、2 位小数")
    private BigDecimal price;

    /** 原价，可空 */
    @DecimalMin(value = "0.00", message = "原价不能为负")
    @Digits(integer = 8, fraction = 2, message = "原价最多 8 位整数、2 位小数")
    private BigDecimal originalPrice;

    /** 库存数量 */
    @NotNull(message = "库存不能为空")
    @PositiveOrZero(message = "库存不能为负")
    private Integer stock;

    /** 封面图 objectKey，由上传接口返回 */
    @Size(max = 512, message = "封面图 objectKey 过长")
    private String coverKey;

    /** 图集 objectKey 列表 */
    private List<String> images;

    /** 商品详情 */
    private String description;

    /** 状态：0 下架，1 上架。不传时由服务端决定默认值（下架），避免「一创建就对外可见」 */
    private Integer status;

    /** 排序值 */
    private Integer sort;
}
