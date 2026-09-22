package com.mall.convert.cart;

import com.mall.api.vo.CartItemVO;
import com.mall.api.vo.CartVO;
import com.mall.entity.ProductEntity;
import com.mall.storage.CartItem;
import com.mall.storage.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 购物车 VO 装配工厂。
 *
 * <h3>为什么它没有实现 {@code VoFactory<S, T>} 接口</h3>
 *
 * <p>{@code VoFactory} 的契约是"<b>一个来源对象 → 一个 VO</b>"。而购物车行是
 * <b>两个来源</b>拼出来的：数量与勾选来自 Redis 里的购物车，名称/价格/封面
 * 来自商品表。硬套接口就只能先把两者塞进一个中间对象，纯属为了对齐签名而造类型。</p>
 *
 * <p>约定本身要守的是"<b>VO 只能由工厂装配，不许在 Controller/Service 里 new</b>"，
 * 而不是"每个工厂都必须实现某个泛型接口"。这里保留工厂、如实多收一个参数，
 * 比削足适履更贴近本意。</p>
 *
 * <h3>为什么它是 Spring Bean</h3>
 *
 * <p>需要 {@link FileStorageService} 把库里的 {@code coverKey} 拼成可访问 URL ——
 * 和 {@code ProductVoFactory} 同一个理由。裸 key 绝不能出现在出参里。</p>
 *
 * <h3>可购买判定（{@link #create}）</h3>
 *
 * <p>三种失效各有原因，且<b>一律不自动移出购物车</b>，只在 VO 上打标记：
 * 静默删除会让用户以为东西"莫名消失了"，反而制造工单。</p>
 */
@Component
@RequiredArgsConstructor
public class CartVoFactory {

    private final FileStorageService fileStorageService;

    /**
     * 装配购物车的一行。
     *
     * @param item    购物车侧的"用户意图"，非空
     * @param product 商品侧的"事实"，可能为 null（商品被硬删）
     */
    public CartItemVO create(CartItem item, ProductEntity product) {
        CartItemVO vo = new CartItemVO();
        vo.setProductId(item.productId());
        vo.setQuantity(item.quantity());
        vo.setSelected(item.selected());

        if (product == null) {
            markInvalid(vo, "商品不存在或已被删除");
            vo.setSubtotal(BigDecimal.ZERO);
            return vo;
        }

        vo.setName(product.getName());
        vo.setSubtitle(product.getSubtitle());
        vo.setCoverUrl(fileStorageService.toAccessUrl(product.getCoverKey()));
        vo.setPrice(product.getPrice());
        vo.setOriginalPrice(product.getOriginalPrice());
        vo.setSellerId(product.getSellerId());
        vo.setStock(product.getStock());

        if (product.getStatus() == null || product.getStatus() != 1) {
            markInvalid(vo, "商品已下架");
        } else if (product.getStock() == null || product.getStock() <= 0) {
            markInvalid(vo, "库存不足");
        } else if (product.getStock() < item.quantity()) {
            // 有货但不够买这么多：仍可购买（可减量），只是给出提示
            vo.setAvailable(true);
            vo.setInvalidReason("库存仅剩 " + product.getStock() + " 件");
        } else {
            vo.setAvailable(true);
        }

        // 小计一律服务端算：前端拿 price 自己乘会引入浮点误差，金额必须 BigDecimal
        vo.setSubtotal(multiply(product.getPrice(), item.quantity()));
        return vo;
    }

    /**
     * 批量装配。
     *
     * @param items    购物车条目
     * @param products productId → 商品，由调用方一次性查出来，避免逐条查库造成 N+1
     */
    public List<CartItemVO> createList(List<CartItem> items, Map<Long, ProductEntity> products) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<CartItemVO> vos = new ArrayList<>(items.size());
        for (CartItem item : items) {
            vos.add(create(item, products == null ? null : products.get(item.productId())));
        }
        return vos;
    }

    /**
     * 把明细汇总成整车视图。
     *
     * <p><b>合计只统计「已勾选 且 可购买」的条目</b>。若把失效商品也算进去，
     * 用户会在结算时发现实付金额突然变小 —— 页面上两个数字对不上，
     * 是最难向用户解释的一类问题。</p>
     */
    public CartVO createCart(List<CartItemVO> items) {
        CartVO cart = new CartVO();
        List<CartItemVO> safeItems = items == null ? List.of() : items;
        cart.setItems(safeItems);
        cart.setTotalCount(safeItems.size());

        int invalidCount = 0;
        int selectedCount = 0;
        int totalQuantity = 0;
        BigDecimal selectedAmount = BigDecimal.ZERO;
        int availableCount = 0;
        int availableSelectedCount = 0;

        for (CartItemVO item : safeItems) {
            boolean available = Boolean.TRUE.equals(item.getAvailable());
            boolean selected = Boolean.TRUE.equals(item.getSelected());
            if (!available) {
                invalidCount++;
                continue;
            }
            availableCount++;
            if (selected) {
                availableSelectedCount++;
                selectedCount++;
                totalQuantity += item.getQuantity() == null ? 0 : item.getQuantity();
                selectedAmount = selectedAmount.add(
                        item.getSubtotal() == null ? BigDecimal.ZERO : item.getSubtotal());
            }
        }

        cart.setInvalidCount(invalidCount);
        cart.setSelectedCount(selectedCount);
        cart.setTotalQuantity(totalQuantity);
        cart.setSelectedAmount(selectedAmount.setScale(2, RoundingMode.HALF_UP));
        // 全选 = 存在可购买商品，且它们全都勾上了（失效商品不参与，否则永远无法"全选"）
        cart.setAllSelected(availableCount > 0 && availableCount == availableSelectedCount);
        return cart;
    }

    private void markInvalid(CartItemVO vo, String reason) {
        vo.setAvailable(false);
        vo.setInvalidReason(reason);
    }

    private BigDecimal multiply(BigDecimal price, int quantity) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        return price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }
}
