package com.mall.api.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 购物车中的一行。
 *
 * <p>这个 VO 是"<b>用户意图 + 商品事实</b>"的拼装结果：
 * 数量与勾选来自购物车，名称/价格/封面/可购状态每次实时取自商品表。
 * 所以它不是任何一张表的映射，而是装配出来的视图 —— 这也是它必须有专门工厂
 * （{@code CartVoFactory}）的原因。</p>
 */
@Data
public class CartItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long productId;

    private String name;

    private String subtitle;

    /** 封面图完整地址（由 objectKey 拼出，前端拿不到裸 key） */
    private String coverUrl;

    /** 实时售价 */
    private BigDecimal price;

    /** 划线价 */
    private BigDecimal originalPrice;

    /** 数量（来自购物车） */
    private Integer quantity;

    /** 是否勾选（来自购物车） */
    private Boolean selected;

    /**
     * 小计 = 实时售价 × 数量。
     *
     * <p>由服务端算，不让前端拿 price 自己乘：一旦前端用浮点数算钱，
     * 就会出现 0.1 + 0.2 = 0.30000000000000004 这类问题。
     * 金额一律 BigDecimal，并且只在服务端算。</p>
     */
    private BigDecimal subtotal;

    /** 商品归属卖家，0 = 平台自营。为将来"按店铺拆单"预留 */
    private Long sellerId;

    /** 是否可购买（商品存在、已上架、库存 > 0） */
    private Boolean available;

    /**
     * 不可购买的原因，例如"已下架""库存不足"。
     *
     * <p>注意：失效商品<b>不会</b>被自动从购物车里删掉，只在列表里打上标记。
     * 静默删除会让用户觉得东西"莫名消失"，反而制造客服工单。</p>
     */
    private String invalidReason;

    /** 库存余量，前端可提示"仅剩 N 件" */
    private Integer stock;
}
