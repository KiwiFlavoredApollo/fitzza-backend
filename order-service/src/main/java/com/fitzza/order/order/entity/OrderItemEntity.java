package com.fitzza.order.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "order_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_item_id")
    private Long orderItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private OrderEntity order;

    @Column(name = "option_id", nullable = false)
    private Long optionId;

    @Column(name = "order_qty", nullable = false)
    private Integer orderQty;

    // 주문 시점의 상품 가격 스냅샷이다. 이후 상품 가격이 바뀌어도 변하지 않는다.
    @Column(name = "unit_price", nullable = false)
    private Integer unitPrice;

    @Builder
    private OrderItemEntity(Long optionId, Integer orderQty, Integer unitPrice) {
        this.optionId = optionId;
        this.orderQty = orderQty;
        this.unitPrice = unitPrice;
    }

    void assignOrder(OrderEntity order) {
        this.order = order;
    }
}
