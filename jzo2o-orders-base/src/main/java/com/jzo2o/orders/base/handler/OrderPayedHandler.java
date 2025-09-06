package com.jzo2o.orders.base.handler;

import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.base.model.dto.OrderUpdateStatusDTO;
import com.jzo2o.orders.base.service.IOrdersCommonService;
import com.jzo2o.statemachine.core.StatusChangeEvent;
import com.jzo2o.statemachine.core.StatusChangeHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.IllegalFormatCodePointException;

/**
 * @Description 支付成功之后要执行的动作
 * @Author Administrator
 * @Date 2025/09/01 17:20
 * @Version 1.0
 */
@Component("order_payed")//bean的名称规则，状态机名称_事件名称
@Slf4j
public class OrderPayedHandler implements StatusChangeHandler<OrderSnapshotDTO> {

    @Resource
    private IOrdersCommonService ordersCommonService;
    @Override
    public void handler(String bizId, StatusChangeEvent statusChangeEventEnum, OrderSnapshotDTO bizSnapshot) {
        log.info("订单支付成功之后要执行的动作,,,,,,,,,,");
        OrderUpdateStatusDTO orderUpdateStatusDTO = new OrderUpdateStatusDTO();
        orderUpdateStatusDTO.setId(bizSnapshot.getId());//订单id
        orderUpdateStatusDTO.setOriginStatus(OrderStatusEnum.NO_PAY.getStatus());//原始状态未支付
        orderUpdateStatusDTO.setTargetStatus(OrderStatusEnum.DISPATCHING.getStatus());//目标状态为支付中
        orderUpdateStatusDTO.setPayStatus(OrderPayStatusEnum.PAY_SUCCESS.getStatus());
        orderUpdateStatusDTO.setTradingOrderNo(bizSnapshot.getTradingOrderNo());//交易单号
        orderUpdateStatusDTO.setTransactionId(bizSnapshot.getThirdOrderId());//第三方支付平台的交易单号
        orderUpdateStatusDTO.setPayTime(bizSnapshot.getPayTime());//支付时间
        orderUpdateStatusDTO.setTradingChannel(bizSnapshot.getTradingChannel());//支付渠道
        Integer i = ordersCommonService.updateStatus(orderUpdateStatusDTO);
        if (i<1) {
            throw new CommonException("订单"+bizSnapshot.getId()+"支付成功事件执行动作失败");
        }

    }
}
