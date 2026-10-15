package com.fitzza.order.order.service;

import com.fitzza.order.cart.entity.CartEntity;
import com.fitzza.order.cart.repository.CartRepository;
import com.fitzza.order.global.client.ProductOptionClient;
import com.fitzza.order.global.client.ProductOptionResponse;
import com.fitzza.order.global.dto.PageResponse;
import com.fitzza.order.global.exception.BusinessException;
import com.fitzza.order.global.exception.ErrorCode;
import com.fitzza.order.order.dto.OrderCreateRequest;
import com.fitzza.order.order.dto.OrderDetailResponse;
import com.fitzza.order.order.dto.OrderListResponse;
import com.fitzza.order.order.entity.OrderEntity;
import com.fitzza.order.order.entity.OrderItemEntity;
import com.fitzza.order.order.entity.OrderStatus;
import com.fitzza.order.order.repository.OrderItemCountProjection;
import com.fitzza.order.order.repository.OrderItemRepository;
import com.fitzza.order.order.repository.OrderRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 상품 서비스 호출 중 DB 커넥션을 점유하지 않도록 쓰기 구간만 TransactionTemplate으로 감싼다.
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Sort ORDER_SORT = Sort.by(Sort.Direction.DESC, "createdAt", "orderId");

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartRepository cartRepository;
    private final ProductOptionClient productOptionClient;
    private final OrderNumberGenerator orderNumberGenerator;
    private final TransactionTemplate transactionTemplate;

    public OrderDetailResponse createOrder(Long userId, OrderCreateRequest request) {
        List<Long> cartIds = request.cartIds().stream().distinct().toList();
        List<CartEntity> carts = findOwnedCarts(userId, cartIds);
        Map<Long, ProductOptionResponse> options =
                productOptionClient.findOptions(carts.stream().map(CartEntity::getOptionId).toList());
        validateSellable(carts, options);
        return transactionTemplate.execute(status -> placeOrder(userId, request, carts, options));
    }

    public PageResponse<OrderListResponse> findOrders(Long userId, int page, int size) {
        Page<OrderEntity> orders = orderRepository.findAllByUserId(userId, PageRequest.of(page, size, ORDER_SORT));
        Map<Long, Long> itemCounts = loadItemCounts(orders.getContent());
        return PageResponse.from(orders.map(order ->
                OrderListResponse.of(order, itemCounts.getOrDefault(order.getOrderId(), 0L))));
    }

    public OrderDetailResponse findOrder(Long userId, Long orderId) {
        OrderEntity order = orderRepository.findWithItemsByOrderIdAndUserId(orderId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        Map<Long, ProductOptionResponse> options = productOptionClient.findOptionsOrEmpty(
                order.getOrderItems().stream().map(OrderItemEntity::getOptionId).toList());
        return OrderDetailResponse.of(order, options);
    }

    private List<CartEntity> findOwnedCarts(Long userId, List<Long> cartIds) {
        List<CartEntity> carts = cartRepository.findAllByCartIdInAndUserIdOrderByCartId(cartIds, userId);
        if (carts.size() != cartIds.size()) {
            throw new BusinessException(ErrorCode.CART_NOT_FOUND);
        }
        return carts;
    }

    private void validateSellable(List<CartEntity> carts, Map<Long, ProductOptionResponse> options) {
        for (CartEntity cart : carts) {
            ProductOptionResponse option = options.get(cart.getOptionId());
            if (option == null || !option.onSale()) {
                throw new BusinessException(ErrorCode.OPTION_NOT_AVAILABLE,
                        "주문할 수 없는 상품 옵션이 포함되어 있습니다. optionId=" + cart.getOptionId());
            }
        }
    }

    private OrderDetailResponse placeOrder(
            Long userId,
            OrderCreateRequest request,
            List<CartEntity> carts,
            Map<Long, ProductOptionResponse> options) {
        List<Long> cartIds = carts.stream().map(CartEntity::getCartId).toList();
        if (cartRepository.deleteByUserIdAndCartIds(userId, cartIds) != carts.size()) {
            throw new BusinessException(ErrorCode.CART_NOT_FOUND);
        }
        OrderEntity order = OrderEntity.builder()
                .orderNum(orderNumberGenerator.generateOrderNumber())
                .userId(userId)
                .totalPrice(calculateTotalPrice(carts, options))
                .orderStatus(OrderStatus.PENDING)
                .recipientName(request.recipientName())
                .recipientPhone(request.recipientPhone())
                .shippingAddress(request.shippingAddress())
                .build();
        carts.forEach(cart -> order.addOrderItem(OrderItemEntity.builder()
                .optionId(cart.getOptionId())
                .orderQty(cart.getQuantity())
                .unitPrice(options.get(cart.getOptionId()).price())
                .build()));
        return OrderDetailResponse.of(orderRepository.saveAndFlush(order), options);
    }

    private long calculateTotalPrice(List<CartEntity> carts, Map<Long, ProductOptionResponse> options) {
        return carts.stream()
                .mapToLong(cart -> (long) options.get(cart.getOptionId()).price() * cart.getQuantity())
                .sum();
    }

    private Map<Long, Long> loadItemCounts(List<OrderEntity> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        List<Long> orderIds = orders.stream().map(OrderEntity::getOrderId).toList();
        return orderItemRepository.countByOrderIds(orderIds).stream()
                .collect(Collectors.toMap(OrderItemCountProjection::getOrderId, OrderItemCountProjection::getItemCount));
    }
}
