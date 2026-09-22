package com.mall.api.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 购物车整车视图。
 *
 * <p>合计类字段（{@link #totalQuantity} / {@link #selectedAmount}）一律由服务端计算。
 * 前端自己再算一遍是重复的真相来源 —— 一旦优惠、运费规则进来，两边必然算出不同结果，
 * 而这种"页面上两个数字对不上"的问题极难解释。</p>
 *
 * <p>另外注意：<b>合计只统计勾选且可购买的商品</b>。失效商品如果被算进金额里，
 * 用户结算时会发现实付金额突然变小。</p>
 */
@Data
public class CartVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 购物车明细，按加购时间升序 */
    private List<CartItemVO> items;

    /** 商品种类数（行数），用于顶部角标 */
    private Integer totalCount;

    /** 已勾选的行数 */
    private Integer selectedCount;

    /** 已勾选且可购买的商品总件数（数量求和） */
    private Integer totalQuantity;

    /** 已勾选且可购买的商品合计金额 */
    private BigDecimal selectedAmount;

    /** 其中失效（不可购买）的行数，前端可提示"有 N 件商品已失效" */
    private Integer invalidCount;

    /** 是否全部勾选，用于驱动"全选"复选框状态 */
    private Boolean allSelected;
}
