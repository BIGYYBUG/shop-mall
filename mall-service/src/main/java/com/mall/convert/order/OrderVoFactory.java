package com.mall.convert.order;

import com.mall.api.vo.OrderItemVO;
import com.mall.api.vo.OrderVO;
import com.mall.convert.VoFactory;
import com.mall.entity.OrderEntity;
import com.mall.entity.OrderItemEntity;
import com.mall.service.order.OrderStatus;
import com.mall.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单 VO 装配工厂。
 *
 * <h3>为什么金额全部直接读库，不重新计算</h3>
 *
 * <p>{@code totalAmount} / {@code payAmount} 在<b>下单那一刻</b>就算好并落库了。
 * 出参时再算一遍看着"更保险"，实际是在给自己挖坑：只要商品改了价、
 * 或者四舍五入规则调整过一次，历史订单的金额就会跟着变 ——
 * 用户翻看三个月前的订单，发现金额和当时付的对不上。</p>
 *
 * <p>订单是<b>凭证</b>。凭证的口径只能有一个：落库时的那个值。</p>
 *
 * <h3>为什么列表接口不带明细</h3>
 *
 * <p>一页 10 条订单，每条平均 3 件商品，带明细就是 30 行数据；
 * 而列表页真正展示的只有单号、状态、金额、件数 —— 件数已经作为
 * 冗余列 {@code item_count} 存在订单主表上了，不需要 join 明细。</p>
 *
 * <p>因此本工厂提供两个重载：{@link #create(OrderEntity)} 用于列表
 * （{@code items} 保持 null），{@link #create(OrderEntity, List)}
 * 用于详情与所有写操作。</p>
 *
 * <h3>为什么它是 Spring Bean</h3>
 *
 * <p>明细里的 {@code productCoverKey} 是 OSS 对象键，必须经
 * {@link FileStorageService} 拼成可访问 URL 才能出参。裸 key 绝不能出现在响应里 ——
 * 本地存储环境下裸 key 可能"碰巧"还能显示（{@code /uploads/**} 映射兜住一部分），
 * 切到 OSS 后才会集中暴露成一片破图。</p>
 */
@Component
@RequiredArgsConstructor
public class OrderVoFactory implements VoFactory<OrderEntity, OrderVO> {

    private final FileStorageService fileStorageService;

    /**
     * 装配订单（不含明细）。
     *
     * <p>用于列表页与分页接口。</p>
     */
    @Override
    public OrderVO create(OrderEntity source) {
        return create(source, null);
    }

    /**
     * 装配订单（含明细）。
     *
     * <p>用于详情页与所有写操作的返回 —— 写操作返回完整体，
     * 前端一轮就能刷新状态与明细，不必再发一次 GET（与购物车同一约定）。</p>
     *
     * @param source 订单主表行
     * @param items  明细行；为 null 时不填充 {@code items} 字段
     */
    public OrderVO create(OrderEntity source, List<OrderItemEntity> items) {
        if (source == null) {
            return null;
        }
        OrderVO vo = new OrderVO();
        vo.setId(source.getId());
        vo.setOrderNo(source.getOrderNo());
        vo.setUserId(source.getUserId());

        vo.setStatus(source.getStatus());
        // 状态文案服务端翻译，避免每个前端各写一份映射
        vo.setStatusText(OrderStatus.text(source.getStatus()));

        vo.setTotalAmount(source.getTotalAmount());
        vo.setFreightAmount(source.getFreightAmount());
        vo.setDiscountAmount(source.getDiscountAmount());
        vo.setPayAmount(source.getPayAmount());
        vo.setItemCount(source.getItemCount());
        vo.setPayType(source.getPayType());

        vo.setReceiverName(source.getReceiverName());
        vo.setReceiverPhone(source.getReceiverPhone());
        vo.setReceiverAddress(source.getReceiverAddress());
        vo.setRemark(source.getRemark());

        vo.setCloseDeadline(source.getCloseDeadline());
        vo.setPayTime(source.getPayTime());
        vo.setShipTime(source.getShipTime());
        vo.setFinishTime(source.getFinishTime());
        vo.setCancelTime(source.getCancelTime());
        vo.setCancelReason(source.getCancelReason());
        vo.setCreateTime(source.getCreateTime());

        if (items != null) {
            vo.setItems(items.stream().map(this::createItem).toList());
        }
        return vo;
    }

    /** 装配一条明细。 */
    public OrderItemVO createItem(OrderItemEntity source) {
        OrderItemVO vo = new OrderItemVO();
        vo.setProductId(source.getProductId());
        vo.setSellerId(source.getSellerId());
        vo.setProductName(source.getProductName());
        vo.setProductCoverUrl(fileStorageService.toAccessUrl(source.getProductCoverKey()));
        vo.setPrice(source.getPrice());
        vo.setQuantity(source.getQuantity());
        vo.setSubtotal(source.getSubtotal());
        return vo;
    }
}
