package com.fitzza.community.client;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

// 상품 서비스가 응답하지 않으면 RestClientException을 그대로 던진다. 확인 없이 넘어가지 않도록 호출한 쪽이 요청을 거부한다.
@Component
public class ProductServiceClient implements ProductCatalog {

    private static final ParameterizedTypeReference<List<ProductSummary>> PRODUCT_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    // 공용 빌더를 그대로 고치면 다른 클라이언트의 주소가 바뀌므로 복사해서 쓴다.
    // product-service의 /internal API는 이 헤더의 값이 INTERNAL_CALL_TOKEN과 같아야 응답한다.
    public ProductServiceClient(
            @LoadBalanced RestClient.Builder restClientBuilder,
            @Value("${fitzza.product-service.base-url:http://product-service}") String baseUrl,
            @Value("${internal.call-token}") String internalCallToken) {
        if (internalCallToken == null || internalCallToken.isBlank()) {
            throw new IllegalArgumentException("INTERNAL_CALL_TOKEN must be set and non-blank");
        }
        this.restClient = restClientBuilder
                .clone()
                .baseUrl(baseUrl)
                .defaultHeader(UserServiceClient.INTERNAL_TOKEN_HEADER, internalCallToken)
                .build();
    }

    @Override
    public boolean isWishlisted(Long userId, long productId) {
        WishlistCheck check = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/wishlists/check")
                        .queryParam("userId", userId)
                        .queryParam("itemId", productId)
                        .build())
                .retrieve()
                .body(WishlistCheck.class);
        return check != null && check.wishlisted();
    }

    @Override
    public Optional<ProductSummary> findProduct(long productId) {
        List<ProductSummary> found = restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/internal/products")
                        .queryParam("productIds", productId)
                        .build())
                .retrieve()
                .body(PRODUCT_LIST);
        if (found == null) {
            return Optional.empty();
        }
        return found.stream()
                .filter(product -> product.productId() != null && product.productId() == productId)
                .findFirst();
    }

    public record WishlistCheck(boolean wishlisted) {
    }
}
