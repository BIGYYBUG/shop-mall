package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.CartItemEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 购物车明细 Mapper。
 *
 * <p>两个方法必须手写 SQL，MyBatis-Plus 的 Wrapper 表达不了：</p>
 * <ul>
 *   <li>{@link #upsertAdd} —— 需要 {@code ON DUPLICATE KEY UPDATE}（UPSERT），
 *       Wrapper 只能生成普通 INSERT；</li>
 *   <li>{@link #batchInsert} —— 需要一条 INSERT 带多组 VALUES 的批量写，
 *       BaseMapper 也没有提供。</li>
 * </ul>
 */
public interface CartItemMapper extends BaseMapper<CartItemEntity> {

    /**
     * 加购：不存在则插入，已存在则<b>在原有数量上累加</b>。
     *
     * <p>必须是单条 SQL 交给 InnoDB 行锁完成，才能避免并发下的丢更新。
     * 加购成功同时把 {@code selected} 置回 1（用户重新加购，默认是"要买"）。</p>
     *
     * @param userId    用户 ID
     * @param productId 商品 ID
     * @param quantity  本次新增数量
     * @return 受影响行数（1 = 插入，2 = 更新；MySQL 的 UPSERT 语义）
     */
    int upsertAdd(@Param("userId") Long userId,
                  @Param("productId") Long productId,
                  @Param("quantity") int quantity);

    /**
     * 批量写入购物车行（刷库用）。
     *
     * <p>调用方需保证这些行的 (user_id, product_id) 互不重复，
     * 且前置已清空该用户的旧行 —— 否则会撞 {@code uk_user_product}。</p>
     *
     * @param items 待写入的行，不可为空
     * @return 插入行数
     */
    int batchInsert(@Param("items") List<CartItemEntity> items);
}
