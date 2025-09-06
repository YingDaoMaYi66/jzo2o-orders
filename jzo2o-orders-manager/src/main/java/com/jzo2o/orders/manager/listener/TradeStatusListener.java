package com.jzo2o.orders.manager.listener;

import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.jzo2o.common.constants.MqConstants;
import com.jzo2o.common.model.msg.TradeStatusMsg;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Description 监听mq获取支付结果
 * @Author Administrator
 * @Date 2025/08/17 13:42
 * @Version 1.0
 */
@Component
@Slf4j
public class TradeStatusListener {
    @Resource
    private IOrdersCreateService ordersCreateService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.Queues.ORDERS_TRADE_UPDATE_STATUS),
            exchange = @Exchange(name = MqConstants.Exchanges.TRADE,type = ExchangeTypes.TOPIC),
            key = MqConstants.RoutingKeys.TRADE_UPDATE_STATUS

    ))
    public void listenTradeUpdatePayStatusMsg(String msg) {
        //解析消息，消息内容是List<TradeStatusMsg>的json格式
        List<TradeStatusMsg> tradeStatusMsgs = JSON.parseArray(msg, TradeStatusMsg.class);

        //根据productAppid系统标识判断是不是自己要处理的消息
        //还需要取出statusCode为4的支付成功的结果
        List<TradeStatusMsg> collect = tradeStatusMsgs
                .stream()
                .filter(item-> "jzo2o.orders".equals(item.getProductAppId())&&item.getStatusCode()==4)
                .collect(Collectors.toList());

        //更新数据库订单表的支付状态和订单状态
        for (TradeStatusMsg tradeStatusMsg : collect) {
            ordersCreateService.paySuccess(tradeStatusMsg);
        }

    }
}
