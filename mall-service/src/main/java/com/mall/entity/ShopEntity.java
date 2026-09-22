package com.mall.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mall.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 店铺实体，对应表 mall_shop —— 卖家的「身份档案」。
 *
 * <h3>为什么需要这张表，而不是给 mall_user 加个 type 字段</h3>
 *
 * <p>一个账号可以同时是买家、卖家、平台运营，身份是<b>可叠加</b>的，
 * 而枚举字段只能选一个。更关键的是「卖家身份」自带两样东西，都不是账号本身能承载的：</p>
 * <ul>
 *   <li><b>生命周期</b> —— 待审核 / 正常 / 驳回 / 冻结，这是一台状态机；</li>
 *   <li><b>专有数据</b> —— 店铺名、Logo、联系方式，这是业务档案。</li>
 * </ul>
 *
 * <p>把三者分开之后，「卖和买到底差在哪」就有了明确答案：</p>
 * <pre>
 *   账号  mall_user              你是谁（登录主体）
 *   身份  mall_shop + 角色        你扮演什么
 *   权限  mall_role/permission    你能干什么
 * </pre>
 *
 * <p>其中「能不能卖」最终<b>落实为权限</b>（{@code seller:*} 系列权限码），
 * 但权限只解决了一半问题 —— 它回答不了「能改哪几件商品」，
 * 那一半由 {@code mall_product.seller_id} 解决。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mall_shop")
public class ShopEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 店主用户 ID。uk_user_id 保证一个账号最多一个有效店铺 */
    private Long userId;

    /** 店铺名称 */
    private String name;

    /** 店铺 Logo 的 objectKey（不是完整 URL，出参时再拼） */
    private String logoKey;

    /** 店铺简介 */
    private String description;

    /** 联系电话 */
    private String contactPhone;

    /**
     * 状态：0 待审核，1 正常，2 已驳回，3 已冻结。
     *
     * <p>用四态而不是布尔，是因为这四种情况的<b>处理动作完全不同</b>：
     * 待审核要等平台处理、驳回要给出原因让店主改、冻结要平台解冻。
     * 压缩成一个 {@code is_seller} 布尔值，这些语义就全丢了。</p>
     */
    private Integer status;

    /** 驳回原因，仅 status = 2 时有意义 */
    private String rejectReason;
}
