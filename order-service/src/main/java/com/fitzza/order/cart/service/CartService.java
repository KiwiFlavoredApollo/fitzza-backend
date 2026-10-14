package com.fitzza.order.cart.service;

import com.fitzza.order.cart.dto.CartCreateRequest;
import com.fitzza.order.cart.dto.CartItemResponse;
import com.fitzza.order.cart.dto.CartResponse;
import com.fitzza.order.cart.dto.CartUpdateRequest;
import com.fitzza.order.cart.entity.CartEntity;
import com.fitzza.order.cart.repository.CartRepository;
import com.fitzza.order.global.client.ProductOptionClient;
import com.fitzza.order.global.client.ProductOptionResponse;
import com.fitzza.order.global.exception.BusinessException;
import com.fitzza.order.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 상품 서비스 호출 중 DB 커넥션을 점유하지 않도록 쓰기 구간만 TransactionTemplate으로 감싼다.
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final ProductOptionClient productOptionClient;
    private final TransactionTemplate transactionTemplate;

    public CartResponse findCart(Long userId) {
        List<CartEntity> carts = cartRepository.findAllByUserIdOrderByCartIdDesc(userId);
        Map<Long, ProductOptionResponse> options =
                productOptionClient.findOptions(carts.stream().map(CartEntity::getOptionId).toList());
        List<CartItemResponse> items = carts.stream()
                .map(cart -> CartItemResponse.of(cart, options.get(cart.getOptionId())))
                .toList();
        return CartResponse.from(items);
    }

    public CartItemResponse addCart(Long userId, CartCreateRequest request) {
        ProductOptionResponse option = requireSellableOption(request.optionId());
        CartEntity cart = saveOrMergeCart(userId, request);
        return CartItemResponse.of(cart, option);
    }

    public CartItemResponse updateCart(Long userId, Long cartId, CartUpdateRequest request) {
        requireOwnedCart(userId, cartId);
        ProductOptionResponse option = requireSellableOption(request.optionId());
        CartEntity cart = saveCartUpdate(userId, cartId, request);
        return CartItemResponse.of(cart, option);
    }

    public void removeCart(Long userId, Long cartId) {
        transactionTemplate.executeWithoutResult(status -> {
            CartEntity cart = cartRepository.findByCartIdAndUserId(cartId, userId)
                    .orElseThrow(() -> ownershipError(cartId));
            cartRepository.delete(cart);
        });
    }

    private CartEntity saveOrMergeCart(Long userId, CartCreateRequest request) {
        try {
            return transactionTemplate.execute(status -> upsertCart(userId, request));
        } catch (DataIntegrityViolationException exception) {
            // 같은 옵션을 동시에 담으면 유니크 제약에 걸리므로 한 번 더 시도해 기존 항목에 합산한다.
            return transactionTemplate.execute(status -> upsertCart(userId, request));
        }
    }

    private CartEntity upsertCart(Long userId, CartCreateRequest request) {
        Optional<CartEntity> existing = cartRepository.findByUserIdAndOptionId(userId, request.optionId());
        if (existing.isPresent()) {
            CartEntity cart = existing.get();
            cart.changeQuantity(sumQuantity(cart.getQuantity(), request.quantity()));
            return cartRepository.saveAndFlush(cart);
        }
        return cartRepository.saveAndFlush(CartEntity.builder()
                .userId(userId)
                .optionId(request.optionId())
                .quantity(request.quantity())
                .build());
    }

    private void requireOwnedCart(Long userId, Long cartId) {
        if (cartRepository.findByCartIdAndUserId(cartId, userId).isEmpty()) {
            throw ownershipError(cartId);
        }
    }

    private BusinessException ownershipError(Long cartId) {
        if (cartRepository.findById(cartId).isPresent()) {
            return new BusinessException(ErrorCode.CART_FORBIDDEN);
        }
        return new BusinessException(ErrorCode.CART_NOT_FOUND);
    }

    private CartEntity saveCartUpdate(Long userId, Long cartId, CartUpdateRequest request) {
        try {
            return transactionTemplate.execute(status -> applyCartUpdate(userId, cartId, request));
        } catch (DataIntegrityViolationException exception) {
            // 옵션을 바꾸는 동안 같은 옵션 항목이 생기면 한 번 더 시도해 기존 항목에 합산한다.
            return transactionTemplate.execute(status -> applyCartUpdate(userId, cartId, request));
        }
    }

    private CartEntity applyCartUpdate(Long userId, Long cartId, CartUpdateRequest request) {
        CartEntity cart = cartRepository.findByCartIdAndUserId(cartId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_NOT_FOUND));
        if (cart.getOptionId().equals(request.optionId())) {
            cart.changeQuantity(request.quantity());
            return cartRepository.saveAndFlush(cart);
        }
        Optional<CartEntity> sameOptionCart = cartRepository.findByUserIdAndOptionId(userId, request.optionId());
        if (sameOptionCart.isPresent()) {
            return mergeIntoExistingCart(cart, sameOptionCart.get(), request.quantity());
        }
        cart.changeOption(request.optionId(), request.quantity());
        return cartRepository.saveAndFlush(cart);
    }

    private CartEntity mergeIntoExistingCart(CartEntity source, CartEntity target, int quantity) {
        target.changeQuantity(sumQuantity(target.getQuantity(), quantity));
        cartRepository.delete(source);
        return cartRepository.saveAndFlush(target);
    }

    // int 범위를 넘기면 음수로 저장되므로, 상한 없이 그 경우만 거절한다.
    private int sumQuantity(int currentQuantity, int addedQuantity) {
        try {
            return Math.addExact(currentQuantity, addedQuantity);
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private ProductOptionResponse requireSellableOption(Long optionId) {
        ProductOptionResponse option = productOptionClient.findOptions(List.of(optionId)).get(optionId);
        if (option == null) {
            throw new BusinessException(ErrorCode.OPTION_NOT_FOUND);
        }
        if (!option.onSale()) {
            throw new BusinessException(ErrorCode.OPTION_NOT_AVAILABLE);
        }
        return option;
    }
}
