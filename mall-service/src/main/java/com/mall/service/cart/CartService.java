package com.mall.service.cart;

/**
 * 购物车服务接口
 */
public interface CartService {

    /**
     * 添加商品到购物车
     *
     * @param userId    用户 ID
     * @param productId 商品 ID
     * @param quantity  数量
     */
    void addToCart(Long userId, Long productId, Integer quantity);
}
