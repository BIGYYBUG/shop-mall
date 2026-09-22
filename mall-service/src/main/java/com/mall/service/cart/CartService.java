package com.mall.service.cart;

import com.mall.api.dto.CartAddDTO;
import com.mall.api.dto.CartSelectDTO;
import com.mall.api.vo.CartVO;

/**
 * 购物车服务。
 *
 * <p>存储方案是 <b>Redis 为主存储、MySQL 为异步备份</b>（见 {@code CartStore} 类注释）。
 * 因此本接口上的每个写方法都只写 Redis 就返回，落库由 {@code CartFlushTask} 在后台批量完成。</p>
 *
 * <h3>为什么写操作也返回整车 {@link CartVO}</h3>
 *
 * <p>前端做完"加购 / 改量 / 勾选"之后，界面上的合计金额、勾选数、角标数量都会变。
 * 若返回 {@code void}，前端每次都要再发一次 GET 才能刷新 —— 一次操作变两次往返，
 * 而且中间那一瞬界面是脏的。直接回整车，一次往返解决，也不会出现"改完数量总额没变"的错觉。</p>
 *
 * <h3>关于 userId</h3>
 *
 * <p>所有方法都收 {@code userId} 作为参数，但它<b>只能来自 {@code UserContext}</b>
 * （即令牌），绝不能来自请求体或查询参数。购物车是最典型的 IDOR 目标：
 * 一旦允许指定 userId，任何人都能读写别人的购物车。</p>
 */
public interface CartService {

    /** 读取整车（含实时商品信息与失效标记）。 */
    CartVO getCart(Long userId);

    /** 加购：已存在则累加数量。 */
    CartVO addToCart(Long userId, CartAddDTO dto);

    /** 把某个商品的数量设为指定值。 */
    CartVO updateQuantity(Long userId, Long productId, Integer quantity);

    /** 批量设置勾选（{@code productIds} 为空表示整车）。 */
    CartVO setSelected(Long userId, CartSelectDTO dto);

    /** 移除单个商品。 */
    CartVO removeItem(Long userId, Long productId);

    /** 清空整车。 */
    void clear(Long userId);

    /** 车内商品种类数，用于顶部角标。 */
    int count(Long userId);
}
