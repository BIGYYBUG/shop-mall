package com.mall.controller;

import com.mall.api.dto.ProductDTO;
import com.mall.api.vo.PageVO;
import com.mall.api.vo.ProductVO;
import com.mall.common.annotation.RequiresPermission;
import com.mall.common.context.UserContext;
import com.mall.common.result.Result;
import com.mall.service.product.ProductService;
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

/**
 * 卖家端 - 我的商品。
 *
 * <h3>与 {@link AdminProductController} 的关系：同一件事，两个范围</h3>
 *
 * <p>路径前缀不同（{@code /seller} vs {@code /admin}）、<b>权限码也完全不同</b>
 * （{@code seller:product:*} vs {@code product:*}），这正是刻意设计的结果：</p>
 *
 * <pre>
 *   PUT /admin/product/{id}   + product:update        → 能改全平台任意商品
 *   PUT /seller/product/{id}  + seller:product:update → 只能改 seller_id = 自己的
 * </pre>
 *
 * <p>如果把两档合并成同一个接口、共用 {@code product:update}，那么给卖家授这个权限
 * 等于把全平台商品交给他 —— 因为 <b>RBAC 的接口级权限只回答「能不能调这个接口」，
 * 回答不了「能操作哪几行数据」</b>。要表达"只能操作自己的"，有两条路：</p>
 * <ol>
 *   <li>把范围写进权限码本身（本项目的做法）—— 切面逻辑一行不改就能区分两档；</li>
 *   <li>在 Service 里按"是否持有平台级权限"决定加不加 {@code seller_id} 条件 ——
 *       条件分支会长在业务代码里，且很容易在新增接口时漏掉。</li>
 * </ol>
 *
 * <h3>sellerId 从哪里来</h3>
 *
 * <p>一律取自 {@link UserContext}（令牌解析出来的当前用户），
 * <b>绝不接受请求参数传入</b>。若把 sellerId 做成查询参数，
 * 卖家只要把参数改成别人的 ID 就能操作别人的商品 ——
 * 这类漏洞（IDOR）在接口级权限校验下完全看不出来，因为他的<em>权限</em>是合法的。</p>
 */
@RestController
@RequestMapping("/seller/product")
@RequiredArgsConstructor
public class SellerProductController {

    private final ProductService productService;

    /**
     * 我的商品分页。
     *
     * @param status 可选过滤：0 下架，1 上架，不传为全部
     */
    @GetMapping("/page")
    @RequiresPermission("seller:product:list")
    public Result<PageVO<ProductVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "10") long pageSize,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) Integer status) {
        return Result.success(productService.pageSellerProducts(
                UserContext.getUserId(), pageNum, pageSize, keyword, status));
    }

    /**
     * 我的商品详情。不属于自己的商品返回 404，不返回 403（避免探测他人商品是否存在）。
     */
    @GetMapping("/{id}")
    @RequiresPermission("seller:product:detail")
    public Result<ProductVO> detail(@PathVariable("id") Long id) {
        return Result.success(productService.getSellerProduct(UserContext.getUserId(), id));
    }

    /**
     * 新增商品。归属由服务端写入，客户端无法指定。
     */
    @PostMapping
    @RequiresPermission("seller:product:create")
    public Result<Long> create(@Valid @RequestBody ProductDTO dto) {
        return Result.success("商品已创建", productService.createSellerProduct(UserContext.getUserId(), dto));
    }

    /**
     * 修改我的商品（不含上下架）
     */
    @PutMapping("/{id}")
    @RequiresPermission("seller:product:update")
    public Result<Void> update(@PathVariable("id") Long id,
                               @Valid @RequestBody ProductDTO dto) {
        productService.updateSellerProduct(UserContext.getUserId(), id, dto);
        return Result.success("修改成功", null);
    }

    /**
     * 上架 / 下架我的商品
     *
     * @param status 0 下架，1 上架
     */
    @PutMapping("/{id}/status")
    @RequiresPermission("seller:product:status")
    public Result<Void> updateStatus(@PathVariable("id") Long id,
                                     @RequestParam("status") Integer status) {
        productService.updateSellerProductStatus(UserContext.getUserId(), id, status);
        return Result.success("状态已更新", null);
    }

    /**
     * 删除我的商品（逻辑删除）
     */
    @DeleteMapping("/{id}")
    @RequiresPermission("seller:product:delete")
    public Result<Void> delete(@PathVariable("id") Long id) {
        productService.deleteSellerProduct(UserContext.getUserId(), id);
        return Result.success("删除成功", null);
    }
}
