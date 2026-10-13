package com.fitzza.order.global.client;

import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
        name = "product-service",
        contextId = "productFeignClient",
        url = "${fitzza.clients.product.url:}")
public interface ProductFeignClient {

    @GetMapping("/product-options")
    List<ProductOptionResponse> getOptions(@RequestParam("optionIds") List<Long> optionIds);
}
