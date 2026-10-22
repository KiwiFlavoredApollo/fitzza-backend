package com.fitzza.order.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitzza.order.order.entity.OrderEntity;
import com.fitzza.order.order.entity.OrderItemEntity;
import com.fitzza.order.order.entity.OrderStatus;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DataJpaTest(properties = {
        "spring.cloud.config.enabled=false",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class OrderRepositoryTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void saveCascadesOrderItemsAndRecordsSnapshot() {
        OrderEntity saved = persistOrder(USER_ID, "ORD-1", 2);
        entityManager.clear();

        OrderEntity found = orderRepository.findById(saved.getOrderId()).orElseThrow();

        assertThat(found.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getOrderItems()).hasSize(2);
        assertThat(found.getOrderItems()).allSatisfy(item -> {
            assertThat(item.getOrderItemId()).isNotNull();
            assertThat(item.getUnitPrice()).isEqualTo(30000);
            assertThat(item.getOrder().getOrderId()).isEqualTo(saved.getOrderId());
        });
    }

    @Test
    void findWithItemsLoadsOrderItemsByOrderId() {
        OrderEntity saved = persistOrder(USER_ID, "ORD-1", 2);
        entityManager.clear();

        var found = orderRepository.findWithItemsByOrderId(saved.getOrderId());

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isEqualTo(USER_ID);
        assertThat(entityManager.getEntityManager().getEntityManagerFactory().getPersistenceUnitUtil()
                .isLoaded(found.get(), "orderItems")).isTrue();
        assertThat(found.get().getOrderItems()).hasSize(2);
        assertThat(found.get().getOrderItems())
                .extracting(OrderItemEntity::getOptionId)
                .containsExactly(10L, 11L);
        assertThat(found.get().getOrderItems())
                .extracting(OrderItemEntity::getOrderItemId)
                .isSorted();
    }

    @Test
    void findWithOrderByOrderItemIdLoadsParentOrder() {
        OrderEntity saved = persistOrder(USER_ID, "ORD-1", 2);
        Long orderItemId = saved.getOrderItems().get(0).getOrderItemId();
        entityManager.clear();

        var found = orderItemRepository.findWithOrderByOrderItemId(orderItemId);

        assertThat(found).isPresent();
        assertThat(found.get().getOrder().getOrderId()).isEqualTo(saved.getOrderId());
        assertThat(found.get().getOrder().getUserId()).isEqualTo(USER_ID);
        assertThat(entityManager.getEntityManager().getEntityManagerFactory().getPersistenceUnitUtil()
                .isLoaded(found.get(), "order")).isTrue();
    }

    @Test
    void findFirstOptionIdsByOrderIdsReturnsEarliestItemOption() {
        OrderEntity twoItems = persistOrder(USER_ID, "ORD-1", 2);
        OrderEntity oneItem = persistOrder(USER_ID, "ORD-2", 1);
        entityManager.clear();

        List<OrderFirstOptionProjection> firstOptions = orderItemRepository.findFirstOptionIdsByOrderIds(
                List.of(twoItems.getOrderId(), oneItem.getOrderId()));

        assertThat(firstOptions)
                .extracting(OrderFirstOptionProjection::getOrderId, OrderFirstOptionProjection::getOptionId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple(twoItems.getOrderId(), 10L),
                        org.assertj.core.api.Assertions.tuple(oneItem.getOrderId(), 10L));
    }

    @Test
    void findAllByUserIdPaginatesNewestFirstForTheUserOnly() {
        persistOrder(USER_ID, "ORD-1", 1);
        OrderEntity latest = persistOrder(USER_ID, "ORD-2", 1);
        persistOrder(OTHER_USER_ID, "ORD-3", 1);
        entityManager.clear();

        Page<OrderEntity> page = orderRepository.findAllByUserId(
                USER_ID, PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "createdAt", "orderId")));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(OrderEntity::getOrderId).containsExactly(latest.getOrderId());
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void countByOrderIdsGroupsItemsPerOrder() {
        OrderEntity twoItems = persistOrder(USER_ID, "ORD-1", 2);
        OrderEntity oneItem = persistOrder(USER_ID, "ORD-2", 1);
        entityManager.clear();

        List<OrderItemCountProjection> counts =
                orderItemRepository.countByOrderIds(List.of(twoItems.getOrderId(), oneItem.getOrderId()));

        assertThat(counts).extracting(OrderItemCountProjection::getOrderId, OrderItemCountProjection::getItemCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple(twoItems.getOrderId(), 2L),
                        org.assertj.core.api.Assertions.tuple(oneItem.getOrderId(), 1L));
    }

    @Test
    void saveRejectsDuplicateOrderNumber() {
        persistOrder(USER_ID, "ORD-1", 1);

        assertThatThrownBy(() -> persistOrder(OTHER_USER_ID, "ORD-1", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private OrderEntity persistOrder(Long userId, String orderNum, int itemCount) {
        OrderEntity order = OrderEntity.builder()
                .orderNum(orderNum)
                .userId(userId)
                .totalPrice(30000L * itemCount)
                .orderStatus(OrderStatus.PENDING)
                .recipientName("홍길동")
                .recipientPhone("010-1234-5678")
                .shippingAddress("서울특별시 강남구")
                .build();
        for (int index = 0; index < itemCount; index++) {
            order.addOrderItem(OrderItemEntity.builder()
                    .optionId(10L + index)
                    .orderQty(1)
                    .unitPrice(30000)
                    .build());
        }
        return orderRepository.saveAndFlush(order);
    }
}
