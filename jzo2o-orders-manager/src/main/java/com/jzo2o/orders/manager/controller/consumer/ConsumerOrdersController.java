package com.jzo2o.orders.manager.controller.consumer;

import cn.hutool.core.bean.BeanUtil;
import com.jzo2o.api.market.dto.response.AvailableCouponsResDTO;
import com.jzo2o.api.orders.dto.request.OrderCancelReqDTO;
import com.jzo2o.api.orders.dto.response.OrderResDTO;
import com.jzo2o.api.orders.dto.response.OrderSimpleResDTO;
import com.jzo2o.common.model.CurrentUserInfo;
import com.jzo2o.common.utils.BeanUtils;
import com.jzo2o.mvc.utils.UserContext;
import com.jzo2o.orders.manager.model.dto.OrderCancelDTO;
import com.jzo2o.orders.manager.model.dto.request.OrdersPayReqDTO;
import com.jzo2o.orders.manager.model.dto.request.PlaceOrderReqDTO;
import com.jzo2o.orders.manager.model.dto.response.OrdersPayResDTO;
import com.jzo2o.orders.manager.model.dto.response.PlaceOrderResDTO;
import com.jzo2o.orders.manager.service.IOrdersCreateService;
import com.jzo2o.orders.manager.service.IOrdersManagerService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * @author itcast
 */
@RestController("consumerOrdersController")
@Api(tags = "用户端-订单相关接口")
@RequestMapping("/consumer/orders")
public class ConsumerOrdersController {

    @Resource
    private IOrdersManagerService ordersManagerService;

    @Resource
    private IOrdersCreateService ordersCreateService;

    @ApiOperation("下单接口")
    @PostMapping("/place")
    public PlaceOrderResDTO place(@RequestBody PlaceOrderReqDTO placeOrderReqDTO) {
        PlaceOrderResDTO placeOrderResDTO = ordersCreateService.palceOrder(placeOrderReqDTO);
        return placeOrderResDTO;
    }


    @GetMapping("/{id}")
    @ApiOperation("根据订单id查询")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "id", value = "订单id", required = true, dataTypeClass = Long.class)
    })
    public OrderResDTO detail(@PathVariable("id") Long id) {
        return ordersManagerService.getDetail(id);
    }
    @GetMapping("/consumerQueryList")
    @ApiOperation("订单滚动分页查询")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "ordersStatus", value = "订单状态，0：待支付，100：派单中，200：待服务，300：服务中，400：待评价，500：订单完成，600：订单取消，700：已关闭", required = false, dataTypeClass = Integer.class),
            @ApiImplicitParam(name = "sortBy", value = "排序字段", required = false, dataTypeClass = Long.class)
    })
    public List<OrderSimpleResDTO> consumerQueryList(@RequestParam(value = "ordersStatus", required = false) Integer ordersStatus,
                                                     @RequestParam(value = "sortBy", required = false) Long sortBy) {
        return ordersManagerService.consumerQueryList(UserContext.currentUserId(), ordersStatus, sortBy);
    }


    @PutMapping("/pay/{id}")
    @ApiOperation("订单支付")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "id", value = "订单id", required = true, dataTypeClass = Long.class)
    })
    public OrdersPayResDTO pay(@PathVariable("id") Long id, @RequestBody OrdersPayReqDTO ordersPayReqDTO) {
        OrdersPayResDTO ordersPayResDTO =ordersCreateService.pay(id, ordersPayReqDTO);
        return ordersPayResDTO;
    }


    @GetMapping("/pay/{id}/result")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "id", value = "订单id", required = true, dataTypeClass = Long.class)
    })
    public OrdersPayResDTO payResult(@PathVariable("id") Long id) {
        //根据订单号查询订单信息，拿到交易单号
        OrdersPayResDTO payResultFromTradServer = ordersCreateService.getPayResultFromTradServer(id);
        //根据交易单号请求支付服务的查询支付结果接口
        //支付成功更新订单表
        return payResultFromTradServer;
    }



    @PutMapping("/cancel")
    @ApiOperation("取消订单")
    public void cancel(@RequestBody OrderCancelReqDTO orderCancelReqDTO) {
        OrderCancelDTO orderCancelDTO = BeanUtils.toBean(orderCancelReqDTO, OrderCancelDTO.class);
        //填充数据
        //用户id
        orderCancelDTO.setCurrentUserId(UserContext.currentUserId());
        //用户名称
        orderCancelDTO.setCurrentUserName(UserContext.currentUser().getName());
        //用户类型
        orderCancelDTO.setCurrentUserType(UserContext.currentUser().getUserType());
        ordersManagerService.cancel(orderCancelDTO);
    }
}
