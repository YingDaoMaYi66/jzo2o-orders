package com.jzo2o.orders.manager.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jzo2o.api.customer.dto.response.AddressBookResDTO;
import com.jzo2o.api.foundations.ServeApi;
import com.jzo2o.api.foundations.dto.response.ServeAggregationResDTO;
import com.jzo2o.api.trade.NativePayApi;
import com.jzo2o.api.trade.TradingApi;
import com.jzo2o.api.trade.dto.request.NativePayReqDTO;
import com.jzo2o.api.trade.dto.response.NativePayResDTO;
import com.jzo2o.api.trade.dto.response.TradingResDTO;
import com.jzo2o.api.trade.enums.PayChannelEnum;
import com.jzo2o.api.trade.enums.TradingStateEnum;
import com.jzo2o.common.expcetions.CommonException;
import com.jzo2o.common.model.msg.TradeStatusMsg;
import com.jzo2o.common.utils.BeanUtils;
import com.jzo2o.common.utils.DateUtils;
import com.jzo2o.common.utils.NumberUtils;
import com.jzo2o.common.utils.ObjectUtils;
import com.jzo2o.mvc.utils.UserContext;
import com.jzo2o.orders.base.config.OrderStateMachine;
import com.jzo2o.orders.base.constants.RedisConstants;
import com.jzo2o.orders.base.enums.OrderPayStatusEnum;
import com.jzo2o.orders.base.enums.OrderStatusChangeEventEnum;
import com.jzo2o.orders.base.enums.OrderStatusEnum;
import com.jzo2o.orders.base.mapper.OrdersMapper;
import com.jzo2o.orders.base.model.domain.Orders;
import com.jzo2o.orders.base.model.dto.OrderSnapshotDTO;
import com.jzo2o.orders.manager.model.dto.request.OrdersPayReqDTO;
import com.jzo2o.orders.manager.model.dto.request.PlaceOrderReqDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.model.dto.response.PlaceOrderResDTO;
import com.jzo2o.orders.manager.porperties.TradeProperties;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.impl.client.CustomerClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 下单服务类
 * </p>
 *
 * @author itcast
 * @since 2023-07-10
 */
@Slf4j
@Service
public class OrdersCreateServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements IOrdersCreateService {


    @Resource
    private CustomerClient customerClient;


    @Resource
    private RedisTemplate redisTemplate;

    @Resource
    private ServeApi serveApi;

    @Resource
    private OrdersCreateServiceImpl owner;

    @Resource
    private NativePayApi nativePayApi;

    @Resource
    private TradingApi tradingApi;

    @Resource
    private OrderStateMachine orderStateMachine;

    /**
     * 读取配置文件
     */
    @Resource
    private TradeProperties tradeProperties;


    /**
     * 生成订单ID 格式：{yyMMdd}{13位序号}
     *
     * @return
     */
    private Long generateOrderId() {

        // 调用Redis自增一个序号
        Long increment = redisTemplate.opsForValue().increment(RedisConstants.Lock.ORDERS_SHARD_KEY_ID_GENERATOR);

        long orderid = DateUtils.getFormatDate(LocalDateTime.now(), "yyMMdd") * 10000000000000L + increment;
        return orderid;
    }

    /**
     * 下单接口
     *
     * @param placeOrderReqDTO 返回
     * @return
     */
    @Override
    public PlaceOrderResDTO palceOrder(PlaceOrderReqDTO placeOrderReqDTO) {
        // 如果开启远程调用,因为网络的原因这个时间相对于本地调用要长，但是开启事务管理后会占用数据库链接，占用数据库资源
        // 下单人的信息
        // 服务地址信息需要远程调用jz020-customer服务查询地址簿信息
        AddressBookResDTO addressBookResDTO = customerClient.getDetail(placeOrderReqDTO.getAddressBookId());


        // 服务信息（相当于商品信息）
        // 需要远程调用jzo2o-foundations服务查询服务信息
        ServeAggregationResDTO serveAggregationResDTO = serveApi.findById(placeOrderReqDTO.getServeId());


        Long orderId = generateOrderId();

        // 准备组装数据
        Orders orders = new Orders();
        // 订单号
        orders.setId(orderId);
        //下单人id
        orders.setUserId(UserContext.currentUserId());

        // 服务类型id
        orders.setServeTypeId(serveAggregationResDTO.getServeTypeId());
        // 服务类型名称
        orders.setServeTypeName(serveAggregationResDTO.getServeTypeName());
        //服务项id
        orders.setServeItemId(serveAggregationResDTO.getServeItemId());
        //服务项名称
        orders.setServeItemName(serveAggregationResDTO.getServeItemName());
        //服务项的图片
        orders.setServeItemImg(serveAggregationResDTO.getServeItemImg());
        //服务单位
        orders.setUnit(serveAggregationResDTO.getUnit());
        //服务id
        orders.setServeId(placeOrderReqDTO.getServeId());
        //订单状态,默认待支付
        orders.setOrdersStatus(OrderStatusEnum.NO_PAY.getStatus());
        //支付状态，默认未支付
        orders.setPayStatus(OrderPayStatusEnum.NO_PAY.getStatus());
        //单价
        orders.setPrice(serveAggregationResDTO.getPrice());
        //购买数量
        orders.setPurNum(placeOrderReqDTO.getPurNum());
        //总价
        BigDecimal totalAmount = serveAggregationResDTO.getPrice().multiply(new BigDecimal(serveAggregationResDTO.getUnit()));
        orders.setTotalAmount(totalAmount);
        //优惠价格
        orders.setRealPayAmount(NumberUtils.sub(orders.getTotalAmount(), orders.getDiscountAmount()));
        // 优惠金额 当前默认0
        orders.setDiscountAmount(BigDecimal.ZERO);
        //city_code
        orders.setCityCode(serveAggregationResDTO.getCityCode());
        //服务地址
        String serveAddress = new StringBuffer().append(addressBookResDTO.getProvince())
                .append(addressBookResDTO.getCity())
                .append(addressBookResDTO.getCounty())
                .append(addressBookResDTO.getAddress())
                .toString();
        orders.setServeAddress(serveAddress);
        //联系人电话
        orders.setContactsPhone(addressBookResDTO.getPhone());
        //联系人姓名
        orders.setContactsName(addressBookResDTO.getName());
        //服务开始时间
        orders.setServeStartTime(placeOrderReqDTO.getServeStartTime());
        //经纬度
        orders.setLon(addressBookResDTO.getLon());
        orders.setLat(addressBookResDTO.getLat());
        //排序字段
        //排序字段，根据服务开始时间装换成毫秒时间，并加上订单后五位
        long sortBy = DateUtils.toEpochMilli(orders.getServeStartTime()) + orders.getId() % 100000;
        orders.setSortBy(sortBy);

        //保存数据
        owner.add(orders);

        //返回数据
        PlaceOrderResDTO placeOrderResDTO = new PlaceOrderResDTO();
        placeOrderResDTO.setId(orders.getId());
        return placeOrderResDTO;
    }

    @Transactional(rollbackFor = Exception.class)
    public void add(Orders orders) {
        //插入数据库
        boolean save = this.save(orders);
        if (!save) {
            throw new CommonException("下单失败");
        }
        //调用状态机的启动方法
        //参数列表:long dbShardId, 分库分表的时候要用，String BizId订单id T bizSnapshot 订单快照
        OrderSnapshotDTO orderSnapshotDTO  = BeanUtils.toBean(orders, OrderSnapshotDTO.class);
        //分库是根据用户id来分的，这里传入用户id
        orderStateMachine.start(orders.getUserId(), orders.getId().toString(),orderSnapshotDTO);
    }


    @Override
    public OrdersPayResDTO pay(Long id, OrdersPayReqDTO ordersPayReqDTO) {

        //查询订单
        Orders orders = getById(id);

        //如果订单不存在直接抛出异常
        if (ObjectUtils.isNull(orders)) {
            throw new CommonException("订单不存在");
        }

        //如果订单已经支付成功则直接返回
        Integer payStatus = orders.getPayStatus();
        if (payStatus == OrderPayStatusEnum.PAY_SUCCESS.getStatus() && ObjectUtils.isNotEmpty(orders.getTradingOrderNo())) {
            OrdersPayResDTO ordersPayResDTO = BeanUtils.toBean(orders, OrdersPayResDTO.class);
            ordersPayResDTO.setProductOrderNo(orders.getId());
            return ordersPayResDTO;
        } else {
            //请求支付服务生成二维码
            NativePayResDTO nativePayResDTO = generateQrCode(orders, ordersPayReqDTO.getTradingChannel());
            OrdersPayResDTO ordersPayResDTO = BeanUtils.toBean(nativePayResDTO, OrdersPayResDTO.class);

            //更新订单表中的交易单号和支付渠道
            boolean update = lambdaUpdate()
                    .eq(Orders::getId, id)
                    .set(Orders::getTradingOrderNo, nativePayResDTO.getTradingOrderNo())
                    .set(Orders::getTradingChannel, nativePayResDTO.getTradingChannel())
                    .update();
            if (!update) {
                log.info("请求支付服务生成二维码，更新订单：{}表的交易单号和支付渠道失败", id);
                throw new CommonException("请求支付服务生成二维码失败,更新订单：" + id + "表的交易单号和支付渠道失败");
            }
            return ordersPayResDTO;
        }

    }

    /**
     * 请求支付服务生成二维码
     *
     * @param orders         订单信息
     * @param tradingChannel 请求的支付渠道
     */
    private NativePayResDTO generateQrCode(Orders orders, PayChannelEnum tradingChannel) {
        //封装请求支付服务的参数
        NativePayReqDTO nativePayDTO = new NativePayReqDTO();

        //根据请求的支付渠道去选择请求使用的是什么商户号
        Long enterpriseId = ObjectUtils.equal(tradingChannel, PayChannelEnum.WECHAT_PAY) ? tradeProperties.getWechatEnterpriseId() : tradeProperties.getAliEnterpriseId();
        //支付的商户号
        nativePayDTO.setEnterpriseId(enterpriseId);
        //家政的订单号
        nativePayDTO.setProductOrderNo(orders.getId());
        //金额
        nativePayDTO.setTradingAmount(orders.getRealPayAmount());
        //业务系统标识，家政订单服务请求支付服务统一指定的jzo2o-orders
        nativePayDTO.setProductAppId("jzo2o-orders");
        //请求的支付渠道
        nativePayDTO.setTradingChannel(tradingChannel);
        //是否切换支付渠道
        //判断如果在订单中有支付渠道并且和新传入的支付渠道不一样,说明用户切换支付渠道
        if (ObjectUtils.isNotEmpty(orders.getTradingChannel()) && ObjectUtils.notEqual(orders.getTradingChannel(), tradingChannel.getValue())) {
            nativePayDTO.setChangeChannel(true);
        }
        //备注，服务项名称
        nativePayDTO.setMemo(orders.getServeItemName());
        //请求支付服务生成二维码
        NativePayResDTO downLineTrading = nativePayApi.createDownLineTrading(nativePayDTO);

        if (ObjectUtils.isNotEmpty(downLineTrading)) {
            return downLineTrading;
        }
        throw new CommonException("请求支付服务生成二维码失败");
    }

    /**
     * 请求支付服务的支付结果
     * @param id
     * @return
     */
    @Override
    public OrdersPayResDTO getPayResultFromTradServer(Long id) {
        //查询订单
        Orders orders = getById(id);

        //如果订单不存在直接抛出异常
        if (ObjectUtils.isNull(orders)) {
            throw new CommonException("订单不存在");
        }
        //支付结果
        Integer payStatus = orders.getPayStatus();
        //如果未支付才去请求支付服务拿到支付结果
        if(payStatus==OrderPayStatusEnum.NO_PAY.getStatus() &&ObjectUtils.isNotEmpty(orders.getTradingOrderNo())) {
            //拿到交易单号
            Long tradingOrderNo = orders.getTradingOrderNo();

            //根据交易单号请求支付服务的查询支付结果接口
            TradingResDTO tradResultByTradingOrderNo = tradingApi.findTradResultByTradingOrderNo(tradingOrderNo);

            //判断是否支付成功，如果支付成功，更新数据库
            if(ObjectUtils.isNotNull(tradResultByTradingOrderNo)&&ObjectUtils.equal(tradResultByTradingOrderNo.getTradingState(), TradingStateEnum.YJS)) {
                TradeStatusMsg tradeStatusMsg = new TradeStatusMsg();
                //订单id
                tradeStatusMsg.setProductOrderNo(id);
                //支付渠道
                tradeStatusMsg.setTradingChannel(tradResultByTradingOrderNo.getTradingChannel());
                //交易单号
                tradeStatusMsg.setTradingOrderNo(tradResultByTradingOrderNo.getTradingOrderNo());
                //第三方（微信）的交易号
                tradeStatusMsg.setTransactionId(tradResultByTradingOrderNo.getTransactionId());
                //支付完成code
                tradeStatusMsg.setStatusCode(OrderPayStatusEnum.PAY_SUCCESS.getStatus());
                //支付成功名称
                tradeStatusMsg.setStatusName(OrderPayStatusEnum.PAY_SUCCESS.name());
                //更新数据库
                owner.paySuccess(tradeStatusMsg);
                //构造返回的数据
                OrdersPayResDTO ordersPayResDTO = BeanUtils.toBean(tradeStatusMsg, OrdersPayResDTO.class);
                //支付成功
                ordersPayResDTO.setPayStatus(OrderPayStatusEnum.PAY_SUCCESS.getStatus());
                return ordersPayResDTO;
            }


        }
        OrdersPayResDTO ordersPayResDTO = BeanUtils.toBean(orders, OrdersPayResDTO.class);
        ordersPayResDTO.setProductOrderNo(orders.getTradingOrderNo());

        return ordersPayResDTO;

    }

    /**
     * 支付成功修改支付服务订单
     * @param tradeStatusMsg
     */
    @Override
    @Transactional
    public void paySuccess(TradeStatusMsg tradeStatusMsg) {
        //支付成功更新订单表
//        boolean update = lambdaUpdate()
//                .eq(Orders::getId, tradeStatusMsg.getProductOrderNo())//订单id
//                .eq(Orders::getOrdersStatus,0)//订单状态只能由待支付变为派单中
//                .set(Orders::getPayStatus, OrderPayStatusEnum.PAY_SUCCESS.getStatus())//gengxin1
//                .set(Orders::getTransactionId, tradeStatusMsg.getTransactionId())
//                .set(Orders::getOrdersStatus, OrderStatusEnum.DISPATCHING.getStatus())
//                .set(Orders::getPayTime, LocalDateTime.now())
//                .update();
//        if(!update){
//            throw new CommonException("支付成功但更新订单"+tradeStatusMsg.getProductOrderNo()+"状态为派单中失败");
//        }
        Orders byId = getById(tradeStatusMsg.getProductOrderNo());
        //使用状态机将待支付状态改为支付中
        OrderSnapshotDTO orderSnapshotDTO  = new OrderSnapshotDTO();
        orderSnapshotDTO.setTradingOrderNo(tradeStatusMsg.getTradingOrderNo());//交易单号
        System.out.println(orderSnapshotDTO.getTradingOrderNo()+"666");
        orderSnapshotDTO.setTradingChannel(tradeStatusMsg.getTradingChannel());//支付渠道
        System.out.println(orderSnapshotDTO.getTradingOrderNo()+"666");
        orderSnapshotDTO.setPayTime(LocalDateTime.now());//支付成功时间
        System.out.println(orderSnapshotDTO.getTradingOrderNo()+"666");
        orderSnapshotDTO.setThirdOrderId(tradeStatusMsg.getTransactionId());//第三方支付平台的交易单号
        System.out.println(orderSnapshotDTO.getTradingOrderNo()+"666");
        orderStateMachine.changeStatus(byId.getUserId(), tradeStatusMsg.getProductOrderNo().toString(), OrderStatusChangeEventEnum.PAYED, orderSnapshotDTO);

    }

    @Override
    public List<Orders> queryOverTimePayOrdersListByCount(Integer count) {
        List<Orders> list = lambdaQuery()
                .eq(Orders::getOrdersStatus, OrderStatusEnum.NO_PAY.getStatus())
                .lt(Orders::getCreateTime, LocalDateTime.now().minusMinutes(15))
                .last("limit " + count)
                .list();
        return list;
    }
}

