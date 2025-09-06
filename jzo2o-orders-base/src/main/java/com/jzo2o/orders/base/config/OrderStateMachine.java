package com.jzo2o.orders.base.config;

import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.statemachine.AbstractStateMachine;
import com.jzo2o.statemachine.core.StatusDefine;
import com.jzo2o.statemachine.persist.StateMachinePersister;
import com.jzo2o.statemachine.snapshot.BizSnapshotService;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * @Description 订单状态机
 * @Author Administrator
 * @Date 2025/09/01 17:32
 * @Version 1.0
 */

public class OrderStateMachine extends AbstractStateMachine<OrderSnapshotDTO> {

    public OrderStateMachine(StateMachinePersister stateMachinePersister, BizSnapshotService bizSnapshotService, RedisTemplate redisTemplate) {
        super(stateMachinePersister, bizSnapshotService, redisTemplate);
    }

    /**
     * 指定状态机的名称
     * @return
     */
    @Override
    protected String getName() {
        return "order";
    }

    @Override
    protected void postProcessor(OrderSnapshotDTO bizSnapshot) {

    }

    //指定订单的初始状态
    @Override
    protected StatusDefine getInitState() {
        return OrderStatusEnum.NO_PAY;
    }
}
