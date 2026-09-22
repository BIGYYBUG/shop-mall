package com.mall.api.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品 VO：出参对象。
 *
 * <p><b>注意与 ProductEntity 的区别</b>：实体里是 {@code coverKey} / {@code images}
 * （存 objectKey），这里对外暴露的是 {@code coverUrl} / {@code imageUrls}
 * （已拼装成可访问地址）。前端不需要知道 OSS 的存储细节，
 * 也就不会被「明天换成 CDN 域名」这种事影响。</p>
 */
@Data
public class ProductVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String name;

    private String subtitle;

    private Long categoryId;

    /**
     * 卖家用户 ID。0 表示平台自营。
     *
     * <p>对前台没有意义（买家不关心谁在卖），但管理端列表需要它来区分
     * 「平台自营商品」和「卖家商品」，排查纠纷时也是第一手线索。</p>
     */
    private Long sellerId;

    private BigDecimal price;

    /** 原价，前端用于展示划线价。可能为 null，前端需自行判空 */
    private BigDecimal originalPrice;

    private Integer stock;

    private Integer sales;

    /** 封面图完整访问地址 */
    private String coverUrl;

    /** 商品图集完整访问地址列表 */
    private List<String> imageUrls;

    private String description;

    /** 状态：0 下架，1 上架 */
    private Integer status;

    private Integer sort;

    private LocalDateTime createTime;
}
