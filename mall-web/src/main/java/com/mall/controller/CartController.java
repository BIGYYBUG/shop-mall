package com.mall.controller;

import com.mall.api.dto.CartAddDTO;
import com.mall.api.dto.CartQuantityDTO;
import com.mall.api.dto.CartSelectDTO;
import com.mall.api.vo.CartVO;
import com.mall.common.context.UserContext;
import com.mall.common.result.Result;
import com.mall.service.cart.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 购物车接口。
 *
 * <h3>权限：全部只要"登录"，没有一个 {@code @RequiresPermission}</h3>
 *
 * <p>购物车是"我自己的东西"，不是"某种角色才能干的事"。买家和卖家都该有自己的车，
 * 而"只能操作自己那辆车"这件事<b>不是靠权限码保证的</b> —— 它靠的是
 * {@code userId} 一律取自 {@link UserContext}（令牌），请求里根本不传 userId。</p>
 *
 * <p>也就是说：<b>RBAC 管"能不能调接口"，数据归属管"能操作哪一行"</b>。
 * 这里两者都用不上额度，因为归属直接被令牌钉死了。</p>
 *
 * <h3>关于返回值</h3>
 * <p>写操作也返回整车 {@link CartVO}（见 {@code CartService} 的说明）：
 * 前端做完操作后无需再发一次 GET，合计金额、勾选数、角标数量一次到位。</p>
 */
@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    /** 读取购物车（含实时价格、库存与失效标记）。 */
    @GetMapping
    public Result<CartVO> get() {
        return Result.success(cartService.getCart(UserContext.getUserId()));
    }

    /** 角标数量：商品种类数。单开一个轻接口，避免为了显示数字拉整车。 */
    @GetMapping("/count")
    public Result<Integer> count() {
        return Result.success(cartService.count(UserContext.getUserId()));
    }

    /** 加入购物车。已存在同一商品则累加数量。 */
    @PostMapping("/items")
    public Result<CartVO> add(@Valid @RequestBody CartAddDTO dto) {
        return Result.success("已加入购物车", cartService.addToCart(UserContext.getUserId(), dto));
    }

    /** 修改某商品的数量（设为指定值，不是累加）。 */
    @PutMapping("/items/{productId}")
    public Result<CartVO> updateQuantity(@PathVariable Long productId,
                                         @Valid @RequestBody CartQuantityDTO dto) {
        return Result.success(cartService.updateQuantity(UserContext.getUserId(), productId, dto.quantity()));
    }

    /** 批量设置勾选；{@code productIds} 为空表示整车（全选 / 取消全选）。 */
    @PutMapping("/items/selected")
    public Result<CartVO> setSelected(@Valid @RequestBody CartSelectDTO dto) {
        return Result.success(cartService.setSelected(UserContext.getUserId(), dto));
    }

    /** 移除单个商品。 */
    @DeleteMapping("/items/{productId}")
    public Result<CartVO> remove(@PathVariable Long productId) {
        return Result.success("已从购物车移除", cartService.removeItem(UserContext.getUserId(), productId));
    }

    /**
     * 批量移除（「删除选中」）。
     *
     * <p><b>为什么用 query 参数而不是请求体</b>：HTTP 规范里 DELETE 带 body 属于未定义行为，
     * 部分网关/代理会直接把它丢掉，前端还看不出异常。放进 query 最稳妥。</p>
     *
     * <p><b>为什么不复用 {@code DELETE /cart/items}</b>：那条已经被"清空"占用了。
     * 同一个 URI 用"参数在不在"区分两种语义（清空 vs 删几条），是很容易误用的歧义设计。</p>
     *
     * <p>路径 {@code /cart/items/batch} 与 {@code /cart/items/{productId}} 不冲突：
     * Spring 的路径匹配优先选择字面量更具体的那个，{@code batch} 不会被当成 productId。</p>
     */
    @DeleteMapping("/items/batch")
    public Result<CartVO> removeBatch(@RequestParam("productIds") List<Long> productIds) {
        return Result.success("已移除选中商品",
                cartService.removeItems(UserContext.getUserId(), productIds));
    }

    /** 清空购物车。 */
    @DeleteMapping("/items")
    public Result<Void> clear() {
        cartService.clear(UserContext.getUserId());
        return Result.success("购物车已清空", null);
    }
}
