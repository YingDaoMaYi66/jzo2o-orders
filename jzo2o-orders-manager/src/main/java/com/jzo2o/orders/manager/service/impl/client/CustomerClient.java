package com.jzo2o.orders.manager.service.impl.client;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.jzo2o.api.customer.AddressBookApi;
import com.jzo2o.api.customer.dto.response.AddressBookResDTO;
import lombok.extern.java.Log;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;

/**
 * @Description 实现降级逻辑的类
 * @Author Administrator
 * @Date 2025/08/09 13:18
 * @Version 1.0
 */
@Component
@Slf4j
public class CustomerClient {

    @Resource
    private AddressBookApi addressBookApi;

    //VALUE:资源名称 将来会在sentinel中作为资源出现，基于资源进行限流熔断
    // fallback 降级逻辑
    @SentinelResource(value = "getAddressBookDetail",fallback = "detailFallback",blockHandler = "detailBlockHandler")
    public AddressBookResDTO getDetail(Long id) {
        log.error("根据id查询地址簿，id：{}",id);
        //调用其他微服务方法
        AddressBookResDTO detail = addressBookApi.detail(id);
        return detail;

    }

    //当getDetail方法执行异常时会调用此方法
    public AddressBookResDTO detailFallback(Long id,Throwable throwable) {
        log.error("非限流，熔断等导致的异常执行的降级方法，id：{}，throwable：",id,throwable);
        return null;
    }

    //熔断，或者限流后的降级逻辑
    public AddressBookResDTO detailBlockHandler(Long id, BlockException blockException) {
        log.error("触发限流，熔断时执行的降级方法，id：{}，blockException：",id,blockException);
        return null;
    }
}
