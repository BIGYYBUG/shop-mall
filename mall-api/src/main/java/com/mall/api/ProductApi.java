package com.mall.api;

import com.mall.api.vo.ProductVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 商品接口定义
 *
 * <p>说明：当前为单体架构下的接口规范（预留 Feign 接口风格）。
 * 未来微服务拆分时，为本接口添加 {@code @FeignClient(name = "mall-product-service")} 注解，
 * 并配合 spring-cloud-starter-openfeign 即可无缝升级为远程调用。</p>
 */
public interface ProductApi {

    /**
     * 根据 ID 查询商品
     *
     * @param id 商品 ID
     * @return 商品信息
     */
    @GetMapping("/product/{id}")
    ProductVO getProductById(@PathVariable("id") Long id);
}
