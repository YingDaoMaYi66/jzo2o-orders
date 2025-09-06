package com.jzo2o.orders.manager.service;
import com.baomidou.mybatisplus.extension.service.IService;
import com.jzo2o.common.model.msg.TradeStatusMsg;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.manager.model.dto.request.OrdersPayReqDTO;
import com.jzo2o.orders.manager.model.dto.request.PlaceOrderReqDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.model.dto.response.PlaceOrderResDTO;

import java.util.List;

/**
 * <p>
 * 下单服务类
 * </p>
 *
 * @author itcast
 * @since 2023-07-10
 */
public interface IOrdersCreateService extends IService<Orders> {
     /**
      * 下单
      * @param placeOrderReqDTO 下单请求信息
      * @return 订单id
      */
     PlaceOrderResDTO palceOrder(PlaceOrderReqDTO placeOrderReqDTO);

     /**
      * 订单支付
      * @param id 订单id
      * @param ordersPayReqDTO 支付渠道
      * @return 订单支付响应体
      */
     OrdersPayResDTO pay(Long id, OrdersPayReqDTO ordersPayReqDTO);

     /**
      * 请求支付服务的支付结果
      * @param id
      * @return
      */
     OrdersPayResDTO getPayResultFromTradServer(Long id);

     /**
      * 支付成功，更新数据库的订单表及其信息
      * @param tradeStatusMsg
      */
     void paySuccess(TradeStatusMsg tradeStatusMsg);

     /**
      * 查询超时订单id列表
      *
      * @param count 数量
      * @return 订单id列表
      */
     public List<Orders> queryOverTimePayOrdersListByCount(Integer count);


}
