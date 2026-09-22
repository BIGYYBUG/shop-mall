package com.mall.service.cart;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 购物车服务实现
 */
@Slf4j
@Service
public class CartServiceImpl implements CartService {

    @Override
    public void addToCart(Long userId, Long productId, Integer quantity) {
        // TODO 对接购物车表 / Redis 缓存，当前为骨架占位
        log.info("添加购物车，userId={}, productId={}, quantity={}", userId, productId, quantity);
    }
}
