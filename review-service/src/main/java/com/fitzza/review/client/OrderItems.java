package com.fitzza.review.client;

import java.util.Optional;

// 리뷰·설문을 쓰기 전에 주문 항목을 주문 서비스에서 확인하는 자리. 테스트에서는 가짜 구현으로 바꿔 끼운다.
public interface OrderItems {

    // 없는 주문 항목이면 빈 값이다. 주문 서비스가 응답하지 않으면 RestClientException을 던진다.
    Optional<OrderItem> findOrderItem(long orderItemId, Long userId);

    // owned: 요청한 사용자의 주문인지. 남의 주문이면 나머지 값은 비어 있을 수 있다.
    record OrderItem(boolean owned, Long productId, Long optionId, String orderStatus) {
    }
}
