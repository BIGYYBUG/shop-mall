package com.mall.service.cart;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.api.dto.CartAddDTO;
import com.mall.api.dto.CartSelectDTO;
import com.mall.api.vo.CartVO;
import com.mall.common.exception.BusinessException;
import com.mall.convert.cart.CartVoFactory;
import com.mall.entity.CartItemEntity;
import com.mall.entity.ProductEntity;
import com.mall.mapper.ProductMapper;
import com.mall.storage.CartItem;
import com.mall.storage.CartStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 购物车服务实现 —— <b>Redis 为主存储，MySQL 只在两处出现</b>：
 * <ol>
 *   <li>加购时校验商品是否可购买（读 {@code mall_product}）；</li>
 *   <li>Redis 里查不到这辆车时回源重建（过期 / 重启后的自愈路径）。</li>
 * </ol>
 * 落库不在这里，由 {@code CartFlushTask} 在后台批量完成 —— 这就是"写只打 Redis"的含义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final CartStore cartStore;
    private final CartPersistenceService cartPersistenceService;
    private final ProductMapper productMapper;
    private final CartVoFactory cartVoFactory;

    @Override
    public CartVO getCart(Long userId) {
        return assemble(userId, loadOrRebuild(userId));
    }

    @Override
    public CartVO addToCart(Long userId, CartAddDTO dto) {
        requireLogin(userId);
        ProductEntity product = productMapper.selectById(dto.productId());
        if (product == null) {
            throw new BusinessException(400, "商品不存在");
        }
        if (product.getStatus() == null || product.getStatus() != 1) {
            throw new BusinessException(400, "商品已下架，无法加入购物车");
        }
        if (product.getStock() == null || product.getStock() <= 0) {
            throw new BusinessException(400, "商品库存不足");
        }

        cartStore.addItem(userId, dto.productId(), dto.quantity());
        return getCart(userId);
    }

    @Override
    public CartVO updateQuantity(Long userId, Long productId, Integer quantity) {
        requireLogin(userId);
        if (quantity == null || quantity < 1) {
            throw new BusinessException(400, "数量至少为 1");
        }
        cartStore.updateQuantity(userId, productId, quantity);
        return getCart(userId);
    }

    @Override
    public CartVO setSelected(Long userId, CartSelectDTO dto) {
        requireLogin(userId);
        boolean selected = Boolean.TRUE.equals(dto.selected());
        if (dto.productIds() == null || dto.productIds().isEmpty()) {
            cartStore.setAllSelected(userId, selected);
        } else {
            cartStore.setSelected(userId, dto.productIds(), selected);
        }
        return getCart(userId);
    }

    @Override
    public CartVO removeItem(Long userId, Long productId) {
        requireLogin(userId);
        cartStore.removeItem(userId, productId);
        return getCart(userId);
    }

    @Override
    public void clear(Long userId) {
        requireLogin(userId);
        cartStore.clear(userId);
    }

    @Override
    public int count(Long userId) {
        if (userId == null) {
            return 0;
        }
        // 角标也要走自愈路径：Redis 刚重启时若直接读 count 会得到 0，
        // 用户会以为购物车丢了，其实数据好好的在 MySQL 里
        return loadOrRebuild(userId).size();
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 读取购物车；若 Redis 里没有这辆车，则回源 MySQL 重建。
     *
     * <p><b>为什么"Redis 没有"不能直接当成空车</b>：Redis 是主存储，它的 key 会因为
     * 过期（30 天）、重启、被清库而消失。此时 MySQL 才是唯一还有数据的地方，
     * 必须回源并顺手把 Redis 重建起来。否则用户下次打开购物车会看到一个空车，
     * 而数据其实完好 —— 这是最容易被误判成"数据丢了"的假故障。</p>
     */
    private List<CartItem> loadOrRebuild(Long userId) {
        List<CartItem> items = cartStore.getItems(userId);
        if (cartStore.exists(userId)) {
            return items;
        }

        List<CartItemEntity> rows = cartPersistenceService.selectByUserId(userId);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<CartItem> rebuilt = rows.stream().map(this::toCartItem).toList();
        cartStore.replaceAll(userId, rebuilt);
        log.info("购物车从 MySQL 回源重建，userId={}，行数={}", userId, rebuilt.size());
        return rebuilt;
    }

    /** 把"用户意图"和"商品事实"拼成整车视图。 */
    private CartVO assemble(Long userId, List<CartItem> items) {
        Map<Long, ProductEntity> products = loadProducts(items);
        return cartVoFactory.createCart(cartVoFactory.createList(items, products));
    }

    /**
     * 一次性把用到的商品查回来。
     *
     * <p>必须批量查（{@code IN}），不能放在循环里逐条 {@code selectById} ——
     * 那是教科书式的 N+1：一辆 20 件商品的车会打出 21 条 SQL。</p>
     */
    private Map<Long, ProductEntity> loadProducts(List<CartItem> items) {
        if (items.isEmpty()) {
            return Map.of();
        }
        List<Long> productIds = items.stream().map(CartItem::productId).distinct().toList();
        List<ProductEntity> found = productMapper.selectList(
                Wrappers.<ProductEntity>lambdaQuery().in(ProductEntity::getId, productIds));
        Map<Long, ProductEntity> map = new HashMap<>(found.size() * 2);
        for (ProductEntity product : found) {
            map.put(product.getId(), product);
        }
        return map;
    }

    private CartItem toCartItem(CartItemEntity entity) {
        long addTime = entity.getCreateTime() == null
                ? System.currentTimeMillis()
                : entity.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new CartItem(
                entity.getProductId(),
                entity.getQuantity() == null ? 1 : entity.getQuantity(),
                entity.getSelected() != null && entity.getSelected() == 1,
                addTime);
    }

    /**
     * 兜底登录校验。
     *
     * <p>正常情况下 {@code /cart/**} 不在放行清单里，{@code JwtInterceptor} 已经把
     * 无令牌的请求挡在 401。这里再判一次是为了防止两种情况：一是将来有人"顺手"
     * 把 {@code /cart/**} 加进放行清单；二是有人绕开 Web 层直接调 Service。</p>
     */
    private void requireLogin(Long userId) {
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
    }

    /** 供刷库任务复用：把 Redis 条目转成待落库的实体行。 */
    static CartItemEntity toEntity(Long userId, CartItem item) {
        CartItemEntity entity = new CartItemEntity();
        entity.setUserId(userId);
        entity.setProductId(item.productId());
        entity.setQuantity(item.quantity());
        entity.setSelected(item.selected() ? 1 : 0);
        entity.setCreateTime(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(item.addTime()), ZoneId.systemDefault()));
        entity.setUpdateTime(LocalDateTime.now());
        return entity;
    }
}
