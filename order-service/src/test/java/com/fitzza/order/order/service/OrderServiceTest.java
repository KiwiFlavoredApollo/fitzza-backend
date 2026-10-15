package com.fitzza.order.order.service;

import static com.fitzza.order.support.TestFixtures.assertErrorCode;
import static com.fitzza.order.support.TestFixtures.cart;
import static com.fitzza.order.support.TestFixtures.option;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fitzza.order.cart.entity.CartEntity;
import com.fitzza.order.cart.repository.CartRepository;
import com.fitzza.order.global.client.ProductOptionClient;
import com.fitzza.order.global.client.ProductOptionResponse;
import com.fitzza.order.global.dto.PageResponse;
import com.fitzza.order.global.exception.BusinessException;
import com.fitzza.order.global.exception.ErrorCode;
import com.fitzza.order.order.dto.OrderCreateRequest;
import com.fitzza.order.order.dto.OrderCreateResponse;
import com.fitzza.order.order.dto.OrderDetailResponse;
import com.fitzza.order.order.dto.OrderListResponse;
import com.fitzza.order.order.entity.OrderEntity;
import com.fitzza.order.order.entity.OrderItemEntity;
import com.fitzza.order.order.entity.OrderStatus;
import com.fitzza.order.order.repository.OrderFirstOptionProjection;
import com.fitzza.order.order.repository.OrderItemCountProjection;
import com.fitzza.order.order.repository.OrderItemRepository;
import com.fitzza.order.order.repository.OrderRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Long USER_ID = 1L;
    private static final String ORDER_NUM = "ORD-20261007-ABCDEF1234";

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductOptionClient productOptionClient;

    @Mock
    private OrderNumberGenerator orderNumberGenerator;

    @Mock
    private PlatformTransactionManager transactionManager;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(
                orderRepository,
                orderItemRepository,
                cartRepository,
                productOptionClient,
                orderNumberGenerator,
                new TransactionTemplate(transactionManager));
    }

    @Test
    void createOrderSnapshotsPricesAndRemovesOrderedCarts() {
        stubOwnedCarts(List.of(2L, 1L), cart(1L, USER_ID, 10L, 2), cart(2L, USER_ID, 11L, 1));
        when(productOptionClient.findOptions(List.of(10L, 11L))).thenReturn(Map.of(
                10L, option(10L, 30000, "ON_SALE"),
                11L, option(11L, 45000, "ON_SALE")));
        when(cartRepository.deleteByUserIdAndCartIds(USER_ID, List.of(1L, 2L))).thenReturn(2);
        when(orderNumberGenerator.generateOrderNumber()).thenReturn(ORDER_NUM);
        when(orderRepository.saveAndFlush(any(OrderEntity.class))).thenAnswer(call -> {
            OrderEntity order = call.getArgument(0);
            ReflectionTestUtils.setField(order, "orderId", 99L);
            return order;
        });

        OrderCreateResponse response = orderService.createOrder(USER_ID, orderRequest(List.of(2L, 1L, 1L)));

        ArgumentCaptor<OrderEntity> captor = ArgumentCaptor.forClass(OrderEntity.class);
        verify(orderRepository).saveAndFlush(captor.capture());
        OrderEntity saved = captor.getValue();
        assertThat(saved.getOrderNum()).isEqualTo(ORDER_NUM);
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getOrderStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(saved.getTotalPrice()).isEqualTo(105000L);
        assertThat(saved.getRecipientName()).isEqualTo("홍길동");
        assertThat(saved.getOrderItems()).extracting(
                        OrderItemEntity::getOptionId, OrderItemEntity::getOrderQty, OrderItemEntity::getUnitPrice)
                .containsExactly(tuple(10L, 2, 30000), tuple(11L, 1, 45000));
        assertThat(response.orderId()).isEqualTo(99L);
        assertThat(response.orderNum()).isEqualTo(ORDER_NUM);
        assertThat(response.totalPrice()).isEqualTo(105000L);
        assertThat(response.orderStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void createOrderRejectsCartsThatDoNotBelongToTheUser() {
        stubOwnedCarts(List.of(1L, 2L), cart(1L, USER_ID, 10L, 1));

        assertErrorCode(() -> orderService.createOrder(USER_ID, orderRequest(List.of(1L, 2L))),
                ErrorCode.CART_NOT_FOUND);
        verifyNoInteractions(productOptionClient);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void createOrderRejectsOptionThatIsNotOnSale() {
        stubOwnedCarts(List.of(1L), cart(1L, USER_ID, 10L, 1));
        when(productOptionClient.findOptions(List.of(10L))).thenReturn(Map.of(10L, option(10L, 30000, "SOLD_OUT")));

        assertErrorCode(() -> orderService.createOrder(USER_ID, orderRequest(List.of(1L))),
                ErrorCode.OPTION_NOT_AVAILABLE);
        verify(cartRepository, never()).deleteByUserIdAndCartIds(any(), any());
    }

    @Test
    void createOrderRejectsOptionMissingFromProductService() {
        stubOwnedCarts(List.of(1L), cart(1L, USER_ID, 10L, 1));
        when(productOptionClient.findOptions(List.of(10L))).thenReturn(Map.of());

        assertErrorCode(() -> orderService.createOrder(USER_ID, orderRequest(List.of(1L))),
                ErrorCode.OPTION_NOT_AVAILABLE);
    }

    @Test
    void createOrderFailsWithoutSavingWhenCartWasRemovedConcurrently() {
        stubOwnedCarts(List.of(1L), cart(1L, USER_ID, 10L, 1));
        when(productOptionClient.findOptions(List.of(10L))).thenReturn(Map.of(10L, option(10L, 30000, "ON_SALE")));
        when(cartRepository.deleteByUserIdAndCartIds(USER_ID, List.of(1L))).thenReturn(0);

        assertErrorCode(() -> orderService.createOrder(USER_ID, orderRequest(List.of(1L))),
                ErrorCode.CART_NOT_FOUND);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void createOrderPropagatesProductServiceFailure() {
        stubOwnedCarts(List.of(1L), cart(1L, USER_ID, 10L, 1));
        when(productOptionClient.findOptions(List.of(10L)))
                .thenThrow(new BusinessException(ErrorCode.PRODUCT_SERVICE_UNAVAILABLE));

        assertErrorCode(() -> orderService.createOrder(USER_ID, orderRequest(List.of(1L))),
                ErrorCode.PRODUCT_SERVICE_UNAVAILABLE);
        verify(cartRepository, never()).deleteByUserIdAndCartIds(any(), any());
    }

    @Test
    void findOrdersReturnsNewestFirstWithItemCountsAndThumbnails() {
        OrderEntity newer = order(2L, "ORD-2", 1);
        OrderEntity older = order(1L, "ORD-1", 2);
        when(orderRepository.findAllByUserId(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(newer, older), PageRequest.of(0, 20), 2));
        when(orderItemRepository.countByOrderIds(List.of(2L, 1L))).thenReturn(List.of(count(2L, 1L), count(1L, 2L)));
        when(orderItemRepository.findFirstOptionIdsByOrderIds(List.of(2L, 1L)))
                .thenReturn(List.of(firstOption(2L, 10L), firstOption(1L, 10L)));
        when(productOptionClient.findOptionsOrEmpty(List.of(10L))).thenReturn(Map.of(
                10L, new ProductOptionResponse(
                        10L, 1010L, "Fitzza", "상품 10", "BLACK", "M", 30000, "https://cdn.example/10.jpg", "ON_SALE")));

        PageResponse<OrderListResponse> response = orderService.findOrders(USER_ID, 0, 20);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findAllByUserId(eq(USER_ID), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(response.content()).extracting(OrderListResponse::orderNum).containsExactly("ORD-2", "ORD-1");
        assertThat(response.content()).extracting(OrderListResponse::itemCount).containsExactly(1L, 2L);
        assertThat(response.content()).extracting(OrderListResponse::thumbnailUrl)
                .containsExactly("https://cdn.example/10.jpg", "https://cdn.example/10.jpg");
        assertThat(response.page()).isEqualTo(0);
        assertThat(response.totalPages()).isEqualTo(1);
    }

    @Test
    void findOrdersSkipsItemCountQueryWhenThereAreNoOrders() {
        when(orderRepository.findAllByUserId(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PageResponse<OrderListResponse> response = orderService.findOrders(USER_ID, 0, 20);

        assertThat(response.content()).isEmpty();
        verifyNoInteractions(orderItemRepository);
        verifyNoInteractions(productOptionClient);
    }

    @Test
    void findOrderRejectsMissingOrder() {
        when(orderRepository.findWithItemsByOrderId(1L)).thenReturn(Optional.empty());

        assertErrorCode(() -> orderService.findOrder(USER_ID, 1L), ErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    void findOrderRejectsOrderOfAnotherUser() {
        OrderEntity otherUsersOrder = order(1L, "ORD-1", 1);
        ReflectionTestUtils.setField(otherUsersOrder, "userId", 99L);
        when(orderRepository.findWithItemsByOrderId(1L)).thenReturn(Optional.of(otherUsersOrder));

        assertErrorCode(() -> orderService.findOrder(USER_ID, 1L), ErrorCode.ORDER_FORBIDDEN);
        verifyNoInteractions(productOptionClient);
    }

    @Test
    void findOrderKeepsSnapshotWhenProductServiceIsUnavailable() {
        when(orderRepository.findWithItemsByOrderId(1L)).thenReturn(Optional.of(order(1L, "ORD-1", 1)));
        when(productOptionClient.findOptionsOrEmpty(List.of(10L))).thenReturn(Map.of());

        OrderDetailResponse response = orderService.findOrder(USER_ID, 1L);

        assertThat(response.recipient().name()).isEqualTo("홍길동");
        assertThat(response.recipient().phone()).isEqualTo("010-1234-5678");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).unitPrice()).isEqualTo(30000);
        assertThat(response.items().get(0).productName()).isNull();
    }

    private void stubOwnedCarts(List<Long> requestedIds, CartEntity... carts) {
        when(cartRepository.findAllByCartIdInAndUserIdOrderByCartId(requestedIds, USER_ID)).thenReturn(List.of(carts));
    }

    private OrderCreateRequest orderRequest(List<Long> cartIds) {
        return new OrderCreateRequest(cartIds, "홍길동", "010-1234-5678", "서울특별시 강남구 테헤란로 1");
    }

    private OrderEntity order(Long orderId, String orderNum, int itemCount) {
        OrderEntity order = OrderEntity.builder()
                .orderNum(orderNum)
                .userId(USER_ID)
                .totalPrice(30000L * itemCount)
                .orderStatus(OrderStatus.PENDING)
                .recipientName("홍길동")
                .recipientPhone("010-1234-5678")
                .shippingAddress("서울특별시 강남구")
                .build();
        ReflectionTestUtils.setField(order, "orderId", orderId);
        for (int index = 0; index < itemCount; index++) {
            order.addOrderItem(OrderItemEntity.builder().optionId(10L + index).orderQty(1).unitPrice(30000).build());
        }
        return order;
    }

    private OrderItemCountProjection count(Long orderId, Long itemCount) {
        return new OrderItemCountProjection() {
            @Override
            public Long getOrderId() {
                return orderId;
            }

            @Override
            public Long getItemCount() {
                return itemCount;
            }
        };
    }

    private OrderFirstOptionProjection firstOption(Long orderId, Long optionId) {
        return new OrderFirstOptionProjection() {
            @Override
            public Long getOrderId() {
                return orderId;
            }

            @Override
            public Long getOptionId() {
                return optionId;
            }
        };
    }
}
