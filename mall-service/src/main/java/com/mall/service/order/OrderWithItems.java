package com.mall.service.order;

import com.mall.entity.OrderEntity;
import com.mall.entity.OrderItemEntity;

import java.util.List;

/**
 * 下单结果：订单主表行 + 刚刚写入的明细行。
 *
 * <h3>为什么把明细一起带回来</h3>
 *
 * <p>落库层在事务内已经把这些明细对象构造好了（它们就是要写进库的那些行）。
 * 如果只返回订单实体、让上层再查一次明细，就是白白的第二次往返 ——
 * 而且这次往返发生在事务提交之后，理论上读到的是"别人可能已经改过"的数据。
 * 直接带回内存里的那份，既省一次查询，也保证返回给用户的就是刚写进去的那份快照。</p>
 *
 * @param order 订单主表行（已含自增主键与订单号）
 * @param items 明细行（已含自增主键）
 */
public record OrderWithItems(OrderEntity order, List<OrderItemEntity> items) {
}
