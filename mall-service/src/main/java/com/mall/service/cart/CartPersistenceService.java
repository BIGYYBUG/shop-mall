package com.mall.service.cart;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.entity.CartItemEntity;
import com.mall.mapper.CartItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 购物车的 MySQL 落库操作。
 *
 * <p><b>为什么会单独有这么个类</b>：定时任务 {@code CartFlushTask} 需要在一个事务里
 * 「先删该用户的旧行、再批量插入新行」。而 {@code @Transactional} 是<b>基于代理</b>生效的
 * —— 在同一个类内部自己调自己（self-invocation）不走代理，注解会静默失效。
 * 把它拆成一个独立 Bean，让定时任务从外部调用，事务才会真正开启。</p>
 *
 * <p>事务在这里是必需的：中间任何一步失败都必须整体回滚。否则一旦"旧行已删、新行没插进去"，
 * 用户重启后回源读到的就是空购物车 —— 一次刷库失败直接变成数据丢失。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartPersistenceService {

    private final CartItemMapper cartItemMapper;

    /**
     * 用给定内容整体替换某用户的购物车（删旧 + 插新，同一事务）。
     *
     * @param rows 新内容；为空表示该用户购物车已被清空，此时只删不插
     */
    @Transactional(rollbackFor = Exception.class)
    public void replaceAll(Long userId, List<CartItemEntity> rows) {
        cartItemMapper.delete(
                Wrappers.<CartItemEntity>lambdaQuery().eq(CartItemEntity::getUserId, userId));
        if (rows != null && !rows.isEmpty()) {
            cartItemMapper.batchInsert(rows);
        }
    }

    /** 读取某用户在 MySQL 里的购物车行（Redis 丢失时回源用）。 */
    public List<CartItemEntity> selectByUserId(Long userId) {
        return cartItemMapper.selectList(
                Wrappers.<CartItemEntity>lambdaQuery()
                        .eq(CartItemEntity::getUserId, userId)
                        .orderByAsc(CartItemEntity::getCreateTime));
    }
}
