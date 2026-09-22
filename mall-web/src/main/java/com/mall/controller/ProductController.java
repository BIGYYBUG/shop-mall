package com.mall.controller;

import com.mall.api.vo.PageVO;
import com.mall.api.vo.ProductVO;
import com.mall.common.result.Result;
import com.mall.service.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品控制器（前台）。
 *
 * <p><b>这个类上没有任何 {@code @RequiresPermission}</b>，这是有意的：
 * 浏览商品是电商的默认能力，不需要登录。需要保护的是「写」操作，
 * 它们在 {@link AdminProductController} 里。</p>
 */
@RestController
@RequestMapping("/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * 商品列表（只返回已上架商品）
     *
     * @param keyword 商品名 / 卖点模糊搜索
     */
    @GetMapping("/page")
    public Result<PageVO<ProductVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "10") long pageSize,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) Long categoryId) {
        return Result.success(productService.pageOnlineProducts(pageNum, pageSize, keyword, categoryId));
    }

    /**
     * 根据 ID 查询商品详情
     */
    @GetMapping("/{id}")
    public Result<ProductVO> getProductById(@PathVariable("id") Long id) {
        return Result.success(productService.getProductById(id));
    }
}
