package com.mall.api;

import com.mall.api.vo.OrderVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 订单接口定义
 *
 * <p>说明：当前为单体架构下的接口规范（预留 Feign 接口风格）。
 * 未来微服务拆分时，为本接口添加 {@code @FeignClient(name = "mall-order-service")} 注解，
 * 并配合 spring-cloud-starter-openfeign 即可无缝升级为远程调用。</p>
 */
public interface OrderApi {

    /**
     * 根据 ID 查询订单
     *
     * @param id 订单 ID
     * @return 订单信息
     */
    @GetMapping("/order/{id}")
    OrderVO getOrderById(@PathVariable("id") Long id);
}
