package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.OrderItemEntity;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 订单明细 Mapper。
 *
 * <p>两个方法都需要手写 SQL：一条 INSERT 带多组 VALUES 的批量写
 * （{@link #batchInsert}）和一次 IN 查询（{@link #selectByOrderIds}），
 * 后者用 Wrapper 也能写，但 XML 里能把"必须自己写 {@code deleted = 0}"
 * 这条约束连同注释一起留在现场。</p>
 */
public interface OrderItemMapper extends BaseMapper<OrderItemEntity> {

    /**
     * 批量写入订单明细（一笔订单的多行，一条 SQL 发出去）。
     *
     * <p>⚠️ 自定义 XML <b>不走 MyBatis-Plus 的自动填充</b>
     * （{@code MybatisFillHandler} 只对 {@code insert()} 等内置方法生效），
     * 而 {@code create_time} / {@code update_time} 都是 NOT NULL，
     * 因此必须在 SQL 里显式写 {@code NOW()}。</p>
     *
     * @param items 明细行，不可为空
     * @return 插入行数
     */
    int batchInsert(@Param("items") List<OrderItemEntity> items);

    /**
     * 按订单 ID 批量取明细。
     *
     * <p><b>为什么必须是"一次查一批"而不是循环单查</b>：订单列表一页 10 条，
     * 逐条查就是 11 条 SQL —— 教科书式的 N+1。批量 IN 一次查完，再在内存里分组。</p>
     *
     * @param orderIds 订单 ID 集合，不可为空
     */
    List<OrderItemEntity> selectByOrderIds(@Param("orderIds") Collection<Long> orderIds);
}
