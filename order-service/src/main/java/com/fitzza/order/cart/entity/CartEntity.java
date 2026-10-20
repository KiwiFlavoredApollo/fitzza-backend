package com.fitzza.order.cart.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "cart", uniqueConstraints = @UniqueConstraint(
        name = "uk_cart_user_option",
        columnNames = {"user_id", "option_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartEntity {

    private static final int DEFAULT_QUANTITY = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cart_id")
    private Long cartId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "option_id", nullable = false)
    private Long optionId;

    @ColumnDefault("1")
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    private CartEntity(Long userId, Long optionId, Integer quantity) {
        this.userId = userId;
        this.optionId = optionId;
        this.quantity = quantity != null ? quantity : DEFAULT_QUANTITY;
    }

    public void changeQuantity(int quantity) {
        this.quantity = quantity;
    }

    public void changeOption(Long optionId, int quantity) {
        this.optionId = optionId;
        this.quantity = quantity;
    }
}
