package com.mall.controller;

import com.mall.api.dto.ShopAuditDTO;
import com.mall.api.dto.ShopDTO;
import com.mall.api.vo.PageVO;
import com.mall.api.vo.ShopVO;
import com.mall.common.annotation.RequiresPermission;
import com.mall.common.result.Result;
import com.mall.service.shop.ShopService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 店铺审核与维护。
 *
 * <p>这里不存在 {@code POST /admin/shop}：店铺只能由用户自己申请创建，
 * 平台不能凭空给别人开店。平台的能力是<b>审核</b>与<b>维护</b>，
 * 不是<b>代开</b>。</p>
 *
 * <p>权限码选用 {@code shop:*} 而不是 {@code seller:*} 前缀，语义上对应
 * 「平台对店铺这个资源的管理能力」，与「卖家对自己店铺的操作能力」区分开。</p>
 */
@RestController
@RequestMapping("/admin/shop")
@RequiredArgsConstructor
public class AdminShopController {

    private final ShopService shopService;

    /**
     * 店铺分页列表。
     *
     * <p>排序是按状态升序 —— 待审核(0) 排在最前面，因为审核界面的核心诉求是
     * 把积压的申请处理掉。</p>
     *
     * @param status 0 待审核 / 1 正常 / 2 已驳回 / 3 已冻结，不传为全部
     */
    @GetMapping("/page")
    @RequiresPermission("shop:list")
    public Result<PageVO<ShopVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                       @RequestParam(defaultValue = "10") long pageSize,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Integer status) {
        return Result.success(shopService.pageShops(pageNum, pageSize, keyword, status));
    }

    /**
     * 店铺详情
     */
    @GetMapping("/{id}")
    @RequiresPermission("shop:detail")
    public Result<ShopVO> detail(@PathVariable("id") Long id) {
        return Result.success(shopService.getShopForAdmin(id));
    }

    /**
     * 平台修改店铺资料。
     *
     * <p>不受"店铺被冻结则不可修改"的限制 —— 冻结期间往往正需要平台更正店铺信息。</p>
     */
    @PutMapping("/{id}")
    @RequiresPermission("shop:update")
    public Result<Void> update(@PathVariable("id") Long id,
                               @Valid @RequestBody ShopDTO dto) {
        shopService.updateShopForAdmin(id, dto);
        return Result.success("店铺资料已更新", null);
    }

    /**
     * 审核店铺。
     *
     * <p>这一条请求会连带做三件事：改店铺状态、调整店主的 SELLER 角色、
     * 冻结时下架该店全部商品。它们必须在同一事务里 —— 只改了状态却没收回角色，
     * 就会出现「店已冻结但店主还能改商品」的漏洞。</p>
     *
     * @param status 1 通过 / 2 驳回 / 3 冻结（不能传 0）
     */
    @PutMapping("/{id}/audit")
    @RequiresPermission("shop:audit")
    public Result<Void> audit(@PathVariable("id") Long id,
                              @Valid @RequestBody ShopAuditDTO dto) {
        shopService.auditShop(id, dto);
        return Result.success("审核完成", null);
    }
}
