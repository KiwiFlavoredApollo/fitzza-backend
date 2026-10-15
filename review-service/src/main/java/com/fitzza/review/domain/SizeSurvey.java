package com.fitzza.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

// 구매한 상품(주문 항목)의 사이즈 평가. 주문 항목 하나에 한 번만 쓸 수 있고 수정은 없다.
@Entity
@Table(
        name = "size_surveys",
        uniqueConstraints = @UniqueConstraint(
                name = SizeSurvey.USER_ORDER_ITEM_UNIQUE_CONSTRAINT,
                columnNames = {"user_id", "order_item_id"}))
public class SizeSurvey {

    public static final String USER_ORDER_ITEM_UNIQUE_CONSTRAINT = "uk_size_surveys_user_order_item";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "survey_id")
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    // 추천 서비스에 사이즈 평가를 보낼 때 함께 보낸다. 이 열이 생기기 전에 쓴 설문은 비어 있어 보내지 않는다.
    @Column(name = "product_id", updatable = false)
    private Long productId;

    // 사이즈별 평가라 주문 항목의 상품 옵션(사이즈)을 함께 저장한다.
    @Column(name = "option_id", nullable = false, updatable = false)
    private Long optionId;

    @Column(name = "order_item_id", nullable = false, updatable = false)
    private Long orderItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "size_feedback", nullable = false, length = 10, updatable = false)
    private SizeFeedback sizeFeedback;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 추천 서비스에 사이즈 평가를 보낸 시각. 비어 있으면 아직 보내지 못한 것이라 재시도 대상이다.
    @Column(name = "feedback_sent_at")
    private Instant feedbackSentAt;

    protected SizeSurvey() {
    }

    private SizeSurvey(
            Long userId, Long productId, Long optionId, Long orderItemId, SizeFeedback sizeFeedback) {
        this.userId = userId;
        this.productId = productId;
        this.optionId = optionId;
        this.orderItemId = orderItemId;
        this.sizeFeedback = sizeFeedback;
    }

    public static SizeSurvey of(
            Long userId, Long productId, Long optionId, Long orderItemId, SizeFeedback sizeFeedback) {
        return new SizeSurvey(userId, productId, optionId, orderItemId, sizeFeedback);
    }

    @PrePersist
    void markCreated() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getOptionId() {
        return optionId;
    }

    public Long getOrderItemId() {
        return orderItemId;
    }

    public SizeFeedback getSizeFeedback() {
        return sizeFeedback;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFeedbackSentAt() {
        return feedbackSentAt;
    }
}
