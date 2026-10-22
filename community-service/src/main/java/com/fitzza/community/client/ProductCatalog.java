package com.fitzza.community.client;

import java.util.Optional;

// 투표 선택지나 공유 글로 고른 찜 상품을 상품 서비스에서 확인하는 자리. 테스트에서는 가짜 구현으로 바꿔 끼운다.
public interface ProductCatalog {

    boolean isWishlisted(Long userId, long productId);

    Optional<ProductSummary> findProduct(long productId);

    record ProductSummary(
            Long productId, String brandName, String productName, Integer price, String imageUrl, String status) {
    }
}
