package com.jzo2o.orders.manager.handler;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jzo2o.api.trade.RefundRecordApi;
import com.jzo2o.api.trade.dto.response.ExecutionResultResDTO;
import com.jzo2o.common.constants.UserType;
import com.jzo2o.common.utils.BeanUtils;
import com.jzo2o.common.utils.CollUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.orders.base.enums.OrderRefundStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.domain.OrdersRefund;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.IOrdersManagerService;
import com.jzo2o.orders.manager.service.IOrdersRefundService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.search.similarities.Lambda;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * @Description 订单定时任务
 * @Author Administrator
 * @Date 2025/08/31 12:17
 * @Version 1.0
 */
@Component
@Slf4j
public class OrdersHandler {
    @Resource
    private IOrdersCreateService ordersCreateService;
    @Resource
    private IOrdersManagerService ordersManagerService;
    @Resource
    private IOrdersRefundService ordersRefundService;
    @Resource
    private RefundRecordApi refundRecordApi;
    @Resource
    private OrdersMapper ordersMapper;


    /**
     * 自动取消支付超时的订单
     * 支付超时：从下单开始超过15分钟还未支付
     */
    @XxlJob("cancelOverTimePayOrder")
    public void cancelOverTimePayOrder() {
        //查询出支付超时的订单
        List<Orders> ordersList = ordersCreateService.queryOverTimePayOrdersListByCount(100);
        if (CollUtils.isEmpty(ordersList)) {
            log.info("自动取消超时订单-未查询到超时订单");
            return;
        }
        //遍历，调用订单取消方法
        for(Orders orders : ordersList) {
            OrderCancelDTO ordersCancelDTO = BeanUtils.toBean(orders, OrderCancelDTO.class);
            //是系统本身的操作，不是c端用户
            ordersCancelDTO.setCurrentUserType(UserType.SYSTEM);
            ordersCancelDTO.setCancelReason("订单超时支付，自动取消");
            ordersManagerService.cancel(ordersCancelDTO);
        }
    }

    /**
     *  退款定时任务
     */
    @XxlJob("handleRefoundOrders")
    public  void handleRefoundOrders() {
        //查询退款记录
        List<OrdersRefund> ordersRefunds = ordersRefundService.queryRefundOrderListByCount(100);
        if (CollUtils.isEmpty(ordersRefunds)) {
            log.info("退款定时任务-未查询到需要退款的订单");
            return;
        }
        //遍历退款记录，请求支付服务进行退款
        for(OrdersRefund ordersRefund : ordersRefunds) {
            //请求支付服务进行退款
            requestRefoundOrder(ordersRefund);
        }

        //如果退款成功更新订单的退款状态

    }

    /**     
     * 请求退款
     * @param ordersRefund 退款记录
     */
    public void requestRefoundOrder(OrdersRefund ordersRefund) {
        ExecutionResultResDTO executionResultResDTO = null;
        //调用支付服务的退款接口
        try{
            executionResultResDTO = refundRecordApi.refundTrading(ordersRefund.getTradingOrderNo(), ordersRefund.getRealPayAmount());

        }catch (Exception e) {
            e.printStackTrace();
        }
        //解析退款结果，如果退款成功，或退款失败,(也就是退款中)更新订单的退款状态
        if(ObjectUtils.isNotNull(executionResultResDTO)&& executionResultResDTO.getRefundStatus()!= OrderRefundStatusEnum.REFUNDING.getStatus()){
            //更新订单的退款状态
            refundOrder(ordersRefund,executionResultResDTO);
        }


    }

    //更新订单的退款状态,只更新要么是退款成功要么是退款失败
    public void refundOrder(OrdersRefund ordersRefund,ExecutionResultResDTO executionResultResDTO) {
        //初始的退款状态为退款中
        Integer refundStatus = OrderRefundStatusEnum.REFUNDING.getStatus();
        //如果支付服务，返回退款成功
        if(executionResultResDTO.getRefundStatus()== OrderRefundStatusEnum.REFUND_SUCCESS.getStatus()){
            refundStatus = OrderRefundStatusEnum.REFUND_SUCCESS.getStatus();
        }else if (executionResultResDTO.getRefundStatus() == OrderRefundStatusEnum.REFUND_FAIL.getStatus()){
            //如果支付服务，返回失败
            refundStatus = OrderRefundStatusEnum.REFUND_FAIL.getStatus();
        }
        //如果是退款中，则程序结束
        if(ObjectUtils.equal(refundStatus,OrderRefundStatusEnum.REFUNDING.getStatus())){
            return;
        }
        //更新订单表的退款状态，支付服务的退款单号，第三方支付平台的退款单号
        LambdaUpdateWrapper<Orders> UpdateWrapper = new LambdaUpdateWrapper<Orders>()
                .eq(Orders::getId, ordersRefund.getId())//订单id
                .ne(Orders::getRefundStatus, refundStatus)//拼接 and refund_status != 退款成功 或 and refund_status != 退款失败
                .set(Orders::getRefundStatus, refundStatus)//设置退款状态
                .set(Orders::getRefundId, executionResultResDTO.getRefundId())
                .set(Orders::getRefundNo, executionResultResDTO.getRefundNo());
        //更新订单表
        int update = ordersMapper.update(null, UpdateWrapper);
        if (update>0){
            //清除退款记录
            ordersRefundService.removeById(ordersRefund.getId());

        }


    }

    /**
     * 新起一个线程请求退款
     */
    public void requestRefundNewThread(Long ordersRefundId) {
        new Thread(() -> {
            //查询退款纪录表
            OrdersRefund ordersRefund = ordersRefundService.getById(ordersRefundId);
            if(ObjectUtils.isEmpty(ordersRefund)){
                //请求支付服务进行退款
                requestRefoundOrder(ordersRefund);
            }
        }).start();

    }


}
