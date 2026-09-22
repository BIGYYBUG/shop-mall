package com.mall.service.order;

import com.mall.api.vo.OrderVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 订单服务实现
 */
@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    @Override
    public OrderVO getOrderById(Long id) {
        // TODO 对接订单 Mapper 查询数据库，当前返回占位数据
        log.info("查询订单，id={}", id);
        OrderVO orderVO = new OrderVO();
        orderVO.setId(id);
        orderVO.setStatus(0);
        return orderVO;
    }
}
