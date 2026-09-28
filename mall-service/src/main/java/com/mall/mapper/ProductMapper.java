package com.mall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.entity.ProductEntity;
import org.apache.ibatis.annotations.Param;

/**
 * 商品 Mapper。
 *
 * <p>常规增删改查由 {@link BaseMapper} 提供。下面三个库存相关的方法是订单域
 * 唯一需要手写 SQL 的地方 —— 因为它们<b>不是"查出来再改"，而是"带条件的原子改"</b>，
 * 这一点用 Wrapper 表达不出来（Wrapper 也无法把 affected rows 当作业务判据来用）。</p>
 *
 * <p><b>为什么不由 ProductService 自己调用</b>：库存扣减是订单域的写操作，
 * 但落点必须在商品表。把它放在 ProductMapper 上、由订单服务调用，
 * 比让两个领域互相注入 Service 更干净（避免 ProductService ⇄ OrderService 循环依赖）。</p>
 */
public interface ProductMapper extends BaseMapper<ProductEntity> {

    /**
     * 扣减库存（原子、带条件）。
     *
     * <pre>
     *   UPDATE mall_product
     *   SET stock = stock - #{quantity}
     *   WHERE id = #{productId} AND status = 1 AND deleted = 0 AND stock >= #{quantity}
     * </pre>
     *
     * <p><b>affected rows = 0 就是"库存不足"或"商品不可购买"</b>，调用方据此抛业务异常。
     * 判定条件必须写在 {@code WHERE} 里：InnoDB 会对这一行做当前读并加行锁，
     * 并发的第二个事务会阻塞到第一个提交、重读后再判断，因此不会超卖。</p>
     *
     * <p>反过来，如果写成"先 selectById 看 stock 够不够、再 update stock = stock - n"，
     * 两个并发请求会都读到 stock = 1、都判定够、都减 1 —— <b>卖出 2 件</b>。
     * 这是超卖最经典的成因。</p>
     *
     * @return 1 = 扣减成功；0 = 库存不足 / 已下架 / 商品不存在
     */
    int deductStock(@Param("productId") Long productId, @Param("quantity") int quantity);

    /**
     * 回补库存（取消订单 / 超时关单时调用）。
     *
     * <pre>
     *   UPDATE mall_product SET stock = stock + #{quantity} WHERE id = #{productId}
     * </pre>
     *
     * <p><b>回补刻意不判断 {@code status} 与 {@code deleted}</b>：商品可能已经下架、
     * 甚至被逻辑删除，但"用户没买成，货得还回去"这件事与商品当前状态无关。
     * 加了条件反而会出现"因为商品下架，库存再也补不回来"的荒谬结果。</p>
     *
     * <p>⚠️ 调用方必须已经通过 {@link OrderMapper#casCancel} 成功把订单置为已取消，
     * 否则会出现重复回补（库存凭空增加）。</p>
     *
     * @return 受影响行数（1 = 已回补；0 = 商品行不存在，属异常数据）
     */
    int restoreStock(@Param("productId") Long productId, @Param("quantity") int quantity);

    /**
     * 累加销量。
     *
     * <p><b>只在下单支付成功时调用，不在下单时调用。</b>理由：销量在语义上应当
     * 单调递增（它代表"卖出去多少"）。如果下单就加、取消时再减，那 sales 的含义
     * 就变成了"当前有效订单件数"，还会出现回退，对外展示会自相矛盾。
     * 而"支付成功"之后再无回退路径（本轮无退款），语义干净。</p>
     *
     * @return 受影响行数
     */
    int increaseSales(@Param("productId") Long productId, @Param("quantity") int quantity);
}
