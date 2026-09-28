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
 *
 * <p><b>为什么参数是订单号而不是自增 id</b>：订单号是外部唯一标识，
 * 拆分服务后它天然是可跨服务传递的业务主键；自增 id 只在本库内唯一，
 * 一旦将来按用户或按时间分库，自增 id 会立刻失去全局唯一性。
 * 从一开始就用业务主键定义契约，是给未来留的余地。</p>
 */
public interface OrderApi {

    /**
     * 根据订单号查询订单（含明细）
     *
     * @param orderNo 订单号
     * @return 订单信息
     */
    @GetMapping("/order/{orderNo}")
    OrderVO getOrderByNo(@PathVariable("orderNo") String orderNo);
}
