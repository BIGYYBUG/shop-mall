package com.mall.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 购物车明细实体，对应表 {@code mall_cart_item}。
 *
 * <h3>为什么不继承 BaseEntity</h3>
 *
 * <p>{@code BaseEntity} 上挂着 {@code @TableLogic} 逻辑删除标记，MyBatis-Plus 会自动
 * 往 {@code deleted} 列写值、并在查询时追加 {@code WHERE deleted = 0}。
 * 而本表是<b>物理删除</b>，根本没有 {@code deleted} 列 —— 继承之后第一步 insert 就会
 * 抛 {@code Unknown column 'deleted'}。</p>
 *
 * <p>这与 {@code mall_user_role} / {@code mall_role_permission} 是同一类情况：
 * <b>表里没有哪些基础列，实体就不能继承带那些列的父类</b>（见 BaseEntity 的类注释）。</p>
 *
 * <h3>为什么这里可以用物理删除</h3>
 *
 * <p>三条判据（与 {@code mall_chat_*} 一致）：非审计实体、产品语义即"真删"、增长最快。
 * 购物车不是账、不是凭证，用户点删除就是要它消失；而加购/删除是最高频的写操作，
 * 用逻辑删除只会让表持续堆积永远不会被读到的垃圾行。</p>
 *
 * <h3>为什么唯一键是 (user_id, product_id)</h3>
 *
 * <p>物理删除下不存在"已删除行"，同一个（用户, 商品）天然只能有一行 —— 于是可以放心用
 * {@code INSERT ... ON DUPLICATE KEY UPDATE quantity = quantity + ?} 一条 SQL 完成
 * <b>原子累加</b>。若写成"先查再写"，两个并发加购会都读到 1、都写回 2（正确应为 3），
 * 这就是丢更新。</p>
 *
 * <p>注意：{@code userId} 一律来自 {@code UserContext}（令牌），<b>绝不接受请求参数</b>，
 * 否则就是把"操作谁的购物车"交给了调用方 —— 典型的 IDOR。</p>
 */
@Data
@TableName("mall_cart_item")
public class CartItemEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 商品 ID */
    private Long productId;

    /** 数量 */
    private Integer quantity;

    /** 结算是否勾选：1 是，0 否 */
    private Integer selected;

    /** 创建时间（首次加购时间） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
