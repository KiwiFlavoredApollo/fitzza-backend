package com.fitzza.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

// 상품 리뷰. 주문 항목 하나에 리뷰 하나만 쓸 수 있고, 삭제하면 행을 지운다.
@Entity
@Table(
        name = "reviews",
        uniqueConstraints = @UniqueConstraint(
                name = Review.ORDER_ITEM_UNIQUE_CONSTRAINT,
                columnNames = "order_item_id"),
        indexes = @Index(name = "idx_reviews_product_created", columnList = "product_id, created_at"))
public class Review {

    public static final String ORDER_ITEM_UNIQUE_CONSTRAINT = "uk_reviews_order_item";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_id")
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "order_item_id", nullable = false, updatable = false)
    private Long orderItemId;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Review() {
    }

    private Review(Long userId, Long productId, Long orderItemId, int rating, String content, String imageUrl) {
        this.userId = userId;
        this.productId = productId;
        this.orderItemId = orderItemId;
        this.rating = rating;
        this.content = content;
        this.imageUrl = imageUrl;
    }

    public static Review create(
            Long userId, Long productId, Long orderItemId, int rating, String content, String imageUrl) {
        return new Review(userId, productId, orderItemId, rating, content, imageUrl);
    }

    public boolean isWrittenBy(Long userId) {
        return this.userId.equals(userId);
    }

    // 수정은 별점·내용·사진을 통째로 바꾼다. 사진을 보내지 않으면 사진을 지운다.
    public void update(int rating, String content, String imageUrl) {
        this.rating = rating;
        this.content = content;
        this.imageUrl = imageUrl;
    }

    @PrePersist
    void markCreated() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void markUpdated() {
        this.updatedAt = Instant.now();
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

    public Long getOrderItemId() {
        return orderItemId;
    }

    public int getRating() {
        return rating;
    }

    public String getContent() {
        return content;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
