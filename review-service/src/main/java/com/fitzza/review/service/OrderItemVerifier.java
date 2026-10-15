package com.fitzza.review.service;

import com.fitzza.review.client.OrderItems;
import com.fitzza.review.client.OrderItems.OrderItem;
import com.fitzza.review.exception.ErrorCode;
import com.fitzza.review.exception.ReviewApiException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

// 리뷰·설문은 내가 주문한(구매 완료된) 상품에만 쓸 수 있다. 주문 서비스가 응답하지 않으면 확인 없이 넘어가지 않고 503으로 거절한다.
// 401·400 같은 4xx는 토큰·요청 설정 문제이므로 그대로 던져 500과 오류 로그로 드러나게 한다.
@Component
public class OrderItemVerifier {

    // 결제·배송 단계가 없는 MVP라 주문은 만들 때 바로 COMPLETED(구매 완료)로 저장된다(2026-10-15 결정, 주문 취소 없음).
    // 구매 완료 주문만 허용한다.
    static final Set<String> REVIEWABLE_ORDER_STATUSES = Set.of("COMPLETED");

    private static final Logger log = LoggerFactory.getLogger(OrderItemVerifier.class);

    private final OrderItems orderItems;

    public OrderItemVerifier(OrderItems orderItems) {
        this.orderItems = orderItems;
    }

    public OrderItem requireReviewable(Long userId, long orderItemId) {
        OrderItem orderItem;
        try {
            orderItem = orderItems.findOrderItem(orderItemId, userId)
                    .orElseThrow(() -> new ReviewApiException(ErrorCode.ORDER_ITEM_NOT_FOUND));
        } catch (ResourceAccessException | HttpServerErrorException exception) {
            log.warn("주문 서비스에서 주문 항목을 확인하지 못해 요청을 거부합니다: {}", exception.getMessage());
            throw new ReviewApiException(ErrorCode.ORDER_LOOKUP_UNAVAILABLE);
        }
        if (!orderItem.owned()) {
            throw new ReviewApiException(ErrorCode.NOT_ORDER_OWNER);
        }
        if (!REVIEWABLE_ORDER_STATUSES.contains(orderItem.orderStatus())) {
            throw new ReviewApiException(ErrorCode.ORDER_NOT_COMPLETED);
        }
        return orderItem;
    }
}
