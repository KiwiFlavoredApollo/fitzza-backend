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

    protected SizeSurvey() {
    }

    private SizeSurvey(Long userId, Long optionId, Long orderItemId, SizeFeedback sizeFeedback) {
        this.userId = userId;
        this.optionId = optionId;
        this.orderItemId = orderItemId;
        this.sizeFeedback = sizeFeedback;
    }

    public static SizeSurvey of(Long userId, Long optionId, Long orderItemId, SizeFeedback sizeFeedback) {
        return new SizeSurvey(userId, optionId, orderItemId, sizeFeedback);
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
}
