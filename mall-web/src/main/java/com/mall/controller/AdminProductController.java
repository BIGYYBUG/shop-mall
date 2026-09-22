package com.mall.controller;

import com.mall.api.dto.ProductDTO;
import com.mall.api.vo.PageVO;
import com.mall.api.vo.ProductVO;
import com.mall.common.annotation.RequiresPermission;
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
 * 管理端 - 商品管理。
 *
 * <p>与 {@link AdminUserController} 保持同一套约定：路径统一挂 {@code /admin} 前缀，
 * 每个方法显式声明所需权限点，校验逻辑全在 {@code PermissionAspect} 里。</p>
 *
 * <p><b>为什么上下架是独立权限点</b>：{@code product:update} 是「改商品信息」，
 * {@code product:status} 是「决定它卖不卖」。这两件事在真实团队里经常是不同的人：
 * 运营改文案，主管控制上下架。合成一个权限点就再也拆不开了。</p>
 */
@RestController
@RequestMapping("/admin/product")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService productService;

    /**
     * 商品分页（含下架商品）
     *
     * @param status 可选过滤：0 下架，1 上架，不传为全部
     */
    @GetMapping("/page")
    @RequiresPermission("product:list")
    public Result<PageVO<ProductVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "10") long pageSize,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) Integer status,
                                          @RequestParam(required = false) Long categoryId) {
        return Result.success(productService.pageAdminProducts(pageNum, pageSize, keyword, status, categoryId));
    }

    /**
     * 商品详情（含下架商品，用于编辑页回显）
     */
    @GetMapping("/{id}")
    @RequiresPermission("product:detail")
    public Result<ProductVO> detail(@PathVariable("id") Long id) {
        return Result.success(productService.getProductForAdmin(id));
    }

    /**
     * 新增商品
     */
    @PostMapping
    @RequiresPermission("product:create")
    public Result<Long> create(@Valid @RequestBody ProductDTO dto) {
        return Result.success("商品已创建", productService.createProduct(dto));
    }

    /**
     * 修改商品（不含上下架）
     */
    @PutMapping("/{id}")
    @RequiresPermission("product:update")
    public Result<Void> update(@PathVariable("id") Long id,
                               @Valid @RequestBody ProductDTO dto) {
        productService.updateProduct(id, dto);
        return Result.success("修改成功", null);
    }

    /**
     * 上架 / 下架
     *
     * @param status 0 下架，1 上架
     */
    @PutMapping("/{id}/status")
    @RequiresPermission("product:status")
    public Result<Void> updateStatus(@PathVariable("id") Long id,
                                     @RequestParam("status") Integer status) {
        productService.updateStatus(id, status);
        return Result.success("状态已更新", null);
    }

    /**
     * 删除商品（逻辑删除）
     */
    @DeleteMapping("/{id}")
    @RequiresPermission("product:delete")
    public Result<Void> delete(@PathVariable("id") Long id) {
        productService.deleteProduct(id);
        return Result.success("删除成功", null);
    }
}
