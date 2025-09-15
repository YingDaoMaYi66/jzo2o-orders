package com.jzo2o.orders.manager.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.db.DbRuntimeException;
import cn.hutool.json.JSONUtil;
import co.elastic.clients.elasticsearch.core.rank_eval.RankEvalMetricDiscountedCumulativeGain;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.orders.dto.response.OrderResDTO;
import com.jzo2o.api.orders.dto.response.OrderSimpleResDTO;
import com.jzo2o.common.constants.UserType;
import com.jzo2o.common.enums.EnableStatusEnum;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.utils.BeanUtils;
import com.jzo2o.common.utils.CollUtils;
import com.jzo2o.common.utils.JsonUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.orders.base.config.OrderStateMachine;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderRefundStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusChangeEventEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.domain.OrdersCanceled;
import com.jzo2o.orders.base.model.domain.OrdersRefund;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.base.model.dto.OrderUpdateStatusDTO;
import com.jzo2o.orders.base.service.IOrdersCommonService;
import com.jzo2o.orders.manager.handler.OrdersHandler;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.IOrdersManagerService;
import com.jzo2o.orders.manager.service.IOrdersRefundService;
import com.jzo2o.redis.helper.CacheHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.jzo2o.orders.base.constants.FieldConstants.SORT_BY;
import static com.jzo2o.orders.base.constants.RedisConstants.RedisKey.ORDERS;

/**
 * <p>
 * 订单表 服务实现类 所有订单管理相关的方法
 * </p>
 *
 * @author itcast
 * @since 2023-07-10
 */
@Slf4j
@Service
public class OrdersManagerServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements IOrdersManagerService {

    @Resource
    private OrdersManagerServiceImpl owner;

    @Resource
    private OrdersCanceledServiceImpl ordersCanceledService;

    @Resource
    private IOrdersCommonService ordersCommonService;

    @Resource
    private IOrdersCreateService ordersCreateService;

    @Resource
    private OrdersCreateServiceImpl ordersCreateServiceImpl;

    @Resource
    private IOrdersRefundService ordersRefundService;

    @Resource
    private OrdersHandler ordersHandler;

    @Resource
    private OrderStateMachine orderStateMachine;

    @Resource
    private CacheHelper cacheHelper;

    @Override
    public List<Orders> batchQuery(List<Long> ids) {
        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery().in(Orders::getId, ids).ge(Orders::getUserId, 0);
        return baseMapper.selectList(queryWrapper);
    }

    @Override
    public Orders queryById(Long id) {
        return baseMapper.selectById(id);
    }

//    /**
//     * 滚动分页查询
//     *
//     * @param currentUserId 当前用户id
//     * @param ordersStatus  订单状态，0：待支付，100：派单中，200：待服务，300：服务中，400：待评价，500：订单完成，600：已取消，700：已关闭
//     * @param sortBy        排序字段
//     * @return 订单列表
//     */
//    @Override
//    public List<OrderSimpleResDTO> consumerQueryList(Long currentUserId, Integer ordersStatus, Long sortBy) {
//        //1.构件查询条件
//        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery()
//                .eq(ObjectUtils.isNotNull(ordersStatus), Orders::getOrdersStatus, ordersStatus)
//                .lt(ObjectUtils.isNotNull(sortBy), Orders::getSortBy, sortBy)
//                .eq(Orders::getUserId, currentUserId)
//                .eq(Orders::getDisplay, EnableStatusEnum.ENABLE.getStatus())
//        Page<Orders> queryPage = new Page<>();
//        queryPage.addOrder(OrderItem.desc(SORT_BY));
//        queryPage.setSearchCount(false);
//
//        //2.查询订单列表
//        Page<Orders> ordersPage = baseMapper.selectPage(queryPage, queryWrapper);
//        List<Orders> records = ordersPage.getRecords();
//        List<OrderSimpleResDTO> orderSimpleResDTOS = BeanUtil.copyToList(records, OrderSimpleResDTO.class);
//        return orderSimpleResDTOS;
//
//    }

    /**
     * 滚动分页查询
     *
     * @param currentUserId 当前用户id
     * @param ordersStatus  订单状态，0：待支付，100：派单中，200：待服务，300：服务中，400：待评价，500：订单完成，600：已取消，700：已关闭
     * @param sortBy        排序字段
     * @return 订单列表
     */
    @Override
    public List<OrderSimpleResDTO> consumerQueryList(Long currentUserId, Integer ordersStatus, Long sortBy) {
        //构建出滚动查询sql语句，先查出满足订单条件的订单id
        // select id from orders where user_id=? and orders_status=? and display = 1 and sort_by<? order by sort_by desc limit 10
        LambdaQueryWrapper<Orders> queryWrapper = Wrappers.<Orders>lambdaQuery()
                .eq(ObjectUtils.isNotNull(ordersStatus), Orders::getOrdersStatus, ordersStatus)
                .lt(ObjectUtils.isNotNull(sortBy), Orders::getSortBy, sortBy)
                .eq(Orders::getUserId, currentUserId)
                .eq(Orders::getDisplay, EnableStatusEnum.ENABLE.getStatus())
                .select(Orders::getId);//指定查询的字段是订单ID
        Page<Orders> queryPage = new Page<>();
        //配置分页查询参数
        queryPage.addOrder(OrderItem.desc(SORT_BY));
        queryPage.setSearchCount(false);

        //2.使用覆盖索引查询订单列表(只包括订单id)
        Page<Orders> ordersPage = baseMapper.selectPage(queryPage, queryWrapper);
        //将查询到的列表表提取出订单id
        List<Orders> ordersList = ordersPage.getRecords();
        //提取出订单id
        List<Long> ids = CollUtils.getFieldValues(ordersList, Orders::getId);
        //根据订单id去查询聚聚索引
        //List<Orders> ordersList1 = batchQuery(ids);
        //将订单信息存入缓存，如果缓存有全部内容，或者者缓存有一部分内容，那这一部分内容从缓存中拿取，剩下的从数据库中拿取，然后再进行缓存，缓存成hash形式
        String redisKey = String.format(ORDERS,currentUserId);
        //参数介绍，1:redis的key   2:顶点id作为hashkey  3:函数式编程，如果有一部分id在redis中查询不到，则使用函数式编程自定义查询规则（查询不到缓存的id，查询到最终要映射成什么类）
        List<OrderSimpleResDTO> orderSimpleResDTOS = cacheHelper.<Long, OrderSimpleResDTO>batchGet(redisKey, ids, (noCacheIds, clazz) -> {
            //查不到的查数据库
            List<Orders> ordersList1 = batchQuery(noCacheIds);
            //将ordersList1转换成map
            Map<Long, OrderSimpleResDTO> collect = ordersList1.stream().collect(Collectors.toMap(Orders::getId, o -> BeanUtils.toBean(o, OrderSimpleResDTO.class)));
            return collect;

        }, OrderSimpleResDTO.class, 600L);

//        List<OrderSimpleResDTO> orderSimpleResDTOS = BeanUtil.copyToList(ordersList1, OrderSimpleResDTO.class);
        return orderSimpleResDTOS;

    }

    /**
     * 根据订单id查询
     *
     * @param id 订单id
     * @return 订单详情
     */
    @Override
    public OrderResDTO getDetail(Long id) {
//        Orders orders = queryById(id);
        //懒加载的方式取消支付超时的订单
        //查询订单快照(得到的json)
        String currentSnapshotCache = orderStateMachine.getCurrentSnapshotCache(id.toString());
        //将json转成对象
        OrderSnapshotDTO orderSnapshotDTO = JsonUtils.toBean(currentSnapshotCache, OrderSnapshotDTO.class);

        //通过懒加载方式取消支付超时订单
        orderSnapshotDTO = canalIfPayOvertime(orderSnapshotDTO);
        OrderResDTO orderResDTO = BeanUtil.toBean(orderSnapshotDTO, OrderResDTO.class);
        return orderResDTO;
    }
    /**
     * 懒加载方式取消支付超时的订单
     */
    public OrderSnapshotDTO canalIfPayOvertime(OrderSnapshotDTO orderSnapshotDTO) {
        //订单状态
        Integer ordersStatus = orderSnapshotDTO.getOrdersStatus();
        //判断订单状态是未支付且支付超时(从订单创建时间开始15分钟内未支付)
        if(ordersStatus==OrderStatusEnum.NO_PAY.getStatus()&&orderSnapshotDTO.getCreateTime().isBefore(LocalDateTime.now().minusMonths(15))){
            OrdersPayResDTO payResultFromTradServer = ordersCreateService.getPayResultFromTradServer(orderSnapshotDTO.getId());
            //如果没有支付成功，再执行下面的取消代码
            if (ObjectUtils.isNotNull(payResultFromTradServer) && payResultFromTradServer.getPayStatus()!= OrderPayStatusEnum.PAY_SUCCESS.getStatus()){
                OrderCancelDTO orderCancelDTO = BeanUtil.toBean(orderSnapshotDTO, OrderCancelDTO.class);
                orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
                orderCancelDTO.setCancelReason("订单支付超时系统自动取消");
                cancelByNoPay(orderCancelDTO);

                //上边执行cancelByNoPay方法，因为使用了状态机，取消订单之后
                String currentSnapshot = orderStateMachine.getCurrentSnapshot(orderSnapshotDTO.getId().toString());
                orderSnapshotDTO= JsonUtils.toBean(currentSnapshot, OrderSnapshotDTO.class);
                return orderSnapshotDTO;
            }

        }

        return orderSnapshotDTO;
    }

//    /**
//     * 懒加载方式取消支付超时的订单
//     */
//    public Orders canalIfPayOvertime(Orders orders){
//        //订单状态
//        Integer ordersStatus = orders.getOrdersStatus();
//        //判断订单状态是未支付且支付超时(从订单创建时间开始15分钟内未支付)
//        if(ordersStatus==OrderStatusEnum.NO_PAY.getStatus()&&orders.getCreateTime().isBefore(LocalDateTime.now().minusMonths(15))){
//            OrdersPayResDTO payResultFromTradServer = ordersCreateService.getPayResultFromTradServer(orders.getId());
//            //如果没有支付成功，再执行下面的取消代码
//            if (ObjectUtils.isNotNull(payResultFromTradServer) && payResultFromTradServer.getPayStatus()!= OrderPayStatusEnum.PAY_SUCCESS.getStatus()){
//                OrderCancelDTO orderCancelDTO = BeanUtil.toBean(orders, OrderCancelDTO.class);
//                orderCancelDTO.setCurrentUserType(UserType.SYSTEM);
//                orderCancelDTO.setCancelReason("订单支付超时系统自动取消");
//                cancelByNoPay(orderCancelDTO);
//                //查询最新的信息
//                orders = getById(orders.getId());
//                return orders;
//            }
//
//        }
//
//        return orders;
//    }

    /**
     * 订单评价
     *
     * @param ordersId 订单id
     */
    @Override
    @Transactional
    public void evaluationOrder(Long ordersId) {
//        //查询订单详情
//        Orders orders = queryById(ordersId);
//
//        //构建订单快照
//        OrderSnapshotDTO orderSnapshotDTO = OrderSnapshotDTO.builder()
//                .evaluationTime(LocalDateTime.now())
//                .build();
//
//        //订单状态变更
//        orderStateMachine.changeStatus(orders.getUserId(), orders.getId().toString(), OrderStatusChangeEventEnum.EVALUATE, orderSnapshotDTO);
    }

    /**
     * 订单取消
     *
     * @param orderCancelDTO 订单取消信息
     */
    @Override
    public void cancel(OrderCancelDTO orderCancelDTO) {
        //订单id
        Long orderId = orderCancelDTO.getId();

        //判断该订单是否存在
        Orders orders = getById(orderId);
        if (ObjectUtils.isEmpty(orders)) {
            throw new CommonException("要取消的订单不存在");
        }
        //将订单号中的交易单号信息拷贝到orderCancelDTO
        orderCancelDTO.setTradingOrderNo(orders.getTradingOrderNo());
        orderCancelDTO.setRealPayAmount(orders.getRealPayAmount());
        //取出订单状态
        Integer ordersStatus = orders.getOrdersStatus();
        //对未支付的订单取消的操作
        if (ordersStatus == OrderStatusEnum.NO_PAY.getStatus()) {
            owner.cancelByNoPay(orderCancelDTO);

        } else if (ordersStatus == OrderStatusEnum.DISPATCHING.getStatus()) {//已支付的订单（派单中）取消的操作
            //向数据库保存三条记录
            owner.cancelByDispatching(orderCancelDTO);
            //新启动一个线程请求支付服务进行退款
            ordersHandler.requestRefundNewThread(orderCancelDTO.getId());
        }  else {
            throw new CommonException("当前订单状态不能取消");
        }

    }

    /**
     * 取消未支付的订单
     *
     * @param orderCancelDTO 订单取消信息
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelByNoPay(OrderCancelDTO orderCancelDTO) {

        //添加订单取消记录
        OrdersCanceled ordersCanceled = BeanUtil.toBean(orderCancelDTO, OrdersCanceled.class);
        //取消人的id
        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
        //取消人的名称
        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
        //取消人的类型
        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
        //取消时间
        ordersCanceled.setCancelTime(LocalDateTime.now());
        ordersCanceledService.save(ordersCanceled);

//        //修改订单的状态为已取消
//        OrderUpdateStatusDTO orderUpdateStatusReqDTO = new OrderUpdateStatusDTO();
//        //填充订单id
//        orderUpdateStatusReqDTO.setId(orderCancelDTO.getId());
//        //原始状态-未支付
//        orderUpdateStatusReqDTO.setOriginStatus(OrderStatusEnum.NO_PAY.getStatus());
//        //目标状态-已取消
//        orderUpdateStatusReqDTO.setTargetStatus(OrderStatusEnum.CANCELED.getStatus());
//        Integer i = ordersCommonService.updateStatus(orderUpdateStatusReqDTO);
//        if (i <= 0) {
//            throw new DbRuntimeException("订单取消失败");
//        }
        OrderSnapshotDTO orderSnapshotDTO = new OrderSnapshotDTO();
        orderSnapshotDTO.setId(ordersCanceled.getId());//订单id
        orderSnapshotDTO.setCancelerName(ordersCanceled.getCancelerName());//取消時間
        orderSnapshotDTO.setCancelTime(LocalDateTime.now());//取消时间
        orderSnapshotDTO.setCancelReason(ordersCanceled.getCancelReason());//取消原因
        //参数分别是:分库分表id  订单id  事件枚举  快照
        orderStateMachine.changeStatus(null,orderCancelDTO.getId().toString(), OrderStatusChangeEventEnum.CANCEL,orderSnapshotDTO);


    }


    /**
     * 取消派单中的订单
     * @param orderCancelDTO
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelByDispatching(OrderCancelDTO orderCancelDTO){
        //更新订单状态为已关闭
//添加订单取消记录
        OrdersCanceled ordersCanceled = BeanUtil.toBean(orderCancelDTO, OrdersCanceled.class);
        //取消人的id
        ordersCanceled.setCancellerId(orderCancelDTO.getCurrentUserId());
        //取消人的名称
        ordersCanceled.setCancelerName(orderCancelDTO.getCurrentUserName());
        //取消人的类型
        ordersCanceled.setCancellerType(orderCancelDTO.getCurrentUserType());
        //取消时间
        ordersCanceled.setCancelTime(LocalDateTime.now());
        ordersCanceledService.save(ordersCanceled);

//        //修改订单的状态为已取消
//        OrderUpdateStatusDTO orderUpdateStatusReqDTO = new OrderUpdateStatusDTO();
//        //填充订单id
//        orderUpdateStatusReqDTO.setId(orderCancelDTO.getId());
//        //原始状态-派单中
//        orderUpdateStatusReqDTO.setOriginStatus(OrderStatusEnum.DISPATCHING.getStatus());
//        //目标状态-已关闭
//        orderUpdateStatusReqDTO.setTargetStatus(OrderStatusEnum.CLOSED.getStatus());
//        //设置退款的状态为1退款中
//        orderUpdateStatusReqDTO.setRefundStatus(OrderRefundStatusEnum.REFUNDING.getStatus());
//        Integer i = ordersCommonService.updateStatus(orderUpdateStatusReqDTO);
//        if (i <= 0) {
//            throw new DbRuntimeException("订单取消失败");
//        }

        //使用状态机进行状态修改
        OrderSnapshotDTO orderSnapshotDTO = new OrderSnapshotDTO();
        //构建订单快照
        orderSnapshotDTO.setId(ordersCanceled.getId());//订单id
        orderSnapshotDTO.setCancelerName(ordersCanceled.getCancelerName());//取消中订单的操作人
        orderSnapshotDTO.setCancelTime(LocalDateTime.now());//取消时间
        orderSnapshotDTO.setCancelReason(ordersCanceled.getCancelReason());//取消原因
        //参数分别是:分库分表id  订单id  事件枚举  快照
        orderStateMachine.changeStatus(null,orderCancelDTO.getId().toString(), OrderStatusChangeEventEnum.CLOSE_DISPATCHING_ORDER,orderSnapshotDTO);

        //保存退款记录
        OrdersRefund ordersRefund = BeanUtils.toBean(orderCancelDTO, OrdersRefund.class);
        ordersRefundService.save(ordersRefund);

    }


}
