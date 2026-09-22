package com.mall.controller;

import com.mall.api.dto.ShopDTO;
import com.mall.api.vo.ShopVO;
import com.mall.common.annotation.RequiresPermission;
import com.mall.common.context.UserContext;
import com.mall.common.result.Result;
import com.mall.service.shop.ShopService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 卖家端 - 我的店铺。
 *
 * <h3>为什么三个接口的权限要求不一样</h3>
 * <table border="1">
 *   <caption>店铺接口的权限划分</caption>
 *   <tr><th>接口</th><th>权限</th><th>理由</th></tr>
 *   <tr><td>GET  /mine</td><td>仅需登录</td><td>还没通过审核的人也要能看到自己的申请状态，
 *       此时他连 SELLER 角色都没有</td></tr>
 *   <tr><td>POST /apply</td><td>仅需登录</td><td>「申请成为卖家」当然不能要求先有卖家权限，
 *       否则永远无法提交第一份申请</td></tr>
 *   <tr><td>PUT  /mine</td><td>seller:shop</td><td>维护在营店铺资料属于卖家能力</td></tr>
 * </table>
 *
 * <p>这也是「身份」与「权限」分离带来的直接好处：如果当初把"是不是卖家"
 * 做成了 {@code mall_user.type} 字段，那么 {@code /mine} 和 {@code /apply}
 * 就得对"还不是卖家"的用户特判 —— 而权限模型天然表达得了
 * 「这个接口不需要权限」和「这个接口需要卖家权限」两种状态。</p>
 */
@RestController
@RequestMapping("/seller/shop")
@RequiredArgsConstructor
public class SellerShopController {

    private final ShopService shopService;

    /**
     * 查看自己的店铺（含审核状态与驳回原因）。
     *
     * <p>尚未入驻时返回 404，前端据此展示「申请入驻」表单。</p>
     */
    @GetMapping("/mine")
    public Result<ShopVO> mine() {
        return Result.success(shopService.getMyShop(UserContext.getUserId()));
    }

    /**
     * 申请入驻。已被驳回的店铺可以用这个接口修改资料后重新提交。
     *
     * @return 店铺 ID
     */
    @PostMapping("/apply")
    public Result<Long> apply(@Valid @RequestBody ShopDTO dto) {
        return Result.success("申请已提交，请等待平台审核",
                shopService.applyShop(UserContext.getUserId(), dto));
    }

    /**
     * 修改自己的店铺资料。
     *
     * <p>修改不会触发重新审核，也不会改变店铺状态 —— 改个简介不该让生意停摆。</p>
     */
    @PutMapping("/mine")
    @RequiresPermission("seller:shop")
    public Result<Void> update(@Valid @RequestBody ShopDTO dto) {
        shopService.updateMyShop(UserContext.getUserId(), dto);
        return Result.success("店铺资料已更新", null);
    }
}
