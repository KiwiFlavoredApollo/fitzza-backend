package com.fitzza.order.global.client;

import com.fitzza.order.global.exception.BusinessException;
import com.fitzza.order.global.exception.ErrorCode;
import feign.FeignException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductOptionClient {

    // product-service /product-options는 한 번에 100개까지만 받고, 넘으면 요청 전체를 거부한다.
    static final int LOOKUP_BATCH_SIZE = 100;

    private final ProductFeignClient productFeignClient;

    public Map<Long, ProductOptionResponse> findOptions(Collection<Long> optionIds) {
        try {
            return fetchOptions(optionIds);
        } catch (FeignException exception) {
            log.warn("상품 옵션 조회에 실패했습니다. status={}", exception.status());
            throw new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        }
    }

    // 조회 화면처럼 상품 정보가 없어도 응답할 수 있는 경우에 사용한다.
    public Map<Long, ProductOptionResponse> findOptionsOrEmpty(Collection<Long> optionIds) {
        try {
            return fetchOptions(optionIds);
        } catch (FeignException exception) {
            log.warn("상품 옵션 조회에 실패해 상품 정보 없이 응답합니다. status={}", exception.status());
            return Map.of();
        }
    }

    private Map<Long, ProductOptionResponse> fetchOptions(Collection<Long> optionIds) {
        List<Long> distinctIds = optionIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ProductOptionResponse> options = new HashMap<>();
        for (int start = 0; start < distinctIds.size(); start += LOOKUP_BATCH_SIZE) {
            int end = Math.min(start + LOOKUP_BATCH_SIZE, distinctIds.size());
            options.putAll(indexOptions(distinctIds.subList(start, end)));
        }
        return options;
    }

    private Map<Long, ProductOptionResponse> indexOptions(List<Long> optionIds) {
        return productFeignClient.getOptions(optionIds).stream()
                .collect(Collectors.toMap(ProductOptionResponse::optionId, Function.identity()));
    }
}
