package com.fitzza.order.cart.service;

import static com.fitzza.order.support.TestFixtures.assertErrorCode;
import static com.fitzza.order.support.TestFixtures.cart;
import static com.fitzza.order.support.TestFixtures.option;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitzza.order.cart.dto.CartCreateRequest;
import com.fitzza.order.cart.dto.CartItemResponse;
import com.fitzza.order.cart.dto.CartResponse;
import com.fitzza.order.cart.dto.CartUpdateRequest;
import com.fitzza.order.cart.entity.CartEntity;
import com.fitzza.order.cart.repository.CartRepository;
import com.fitzza.order.global.client.ProductOptionClient;
import com.fitzza.order.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductOptionClient productOptionClient;

    @Mock
    private PlatformTransactionManager transactionManager;

    private CartService cartService;

    @BeforeEach
    void setUp() {
        cartService = new CartService(cartRepository, productOptionClient, new TransactionTemplate(transactionManager));
    }

    @Test
    void findCartTotalsOnlyAvailableItems() {
        when(cartRepository.findAllByUserIdOrderByCartIdDesc(USER_ID)).thenReturn(List.of(
                cart(3L, USER_ID, 12L, 1),
                cart(2L, USER_ID, 11L, 1),
                cart(1L, USER_ID, 10L, 2)));
        when(productOptionClient.findOptions(List.of(12L, 11L, 10L))).thenReturn(Map.of(
                10L, option(10L, 30000, "ON_SALE"),
                11L, option(11L, 45000, "SOLD_OUT")));

        CartResponse response = cartService.findCart(USER_ID);

        assertThat(response.items()).extracting(CartItemResponse::available).containsExactly(false, false, true);
        assertThat(response.items().get(0).productName()).isNull();
        assertThat(response.items().get(1).linePrice()).isZero();
        assertThat(response.items().get(2).linePrice()).isEqualTo(60000);
        assertThat(response.totalQuantity()).isEqualTo(2);
        assertThat(response.totalPrice()).isEqualTo(60000);
    }

    @Test
    void findCartSumsQuantityBeyondIntegerRange() {
        when(cartRepository.findAllByUserIdOrderByCartIdDesc(USER_ID)).thenReturn(List.of(
                cart(2L, USER_ID, 11L, Integer.MAX_VALUE),
                cart(1L, USER_ID, 10L, Integer.MAX_VALUE)));
        when(productOptionClient.findOptions(List.of(11L, 10L))).thenReturn(Map.of(
                10L, option(10L, 30000, "ON_SALE"),
                11L, option(11L, 45000, "ON_SALE")));

        CartResponse response = cartService.findCart(USER_ID);

        assertThat(response.totalQuantity()).isEqualTo(2L * Integer.MAX_VALUE);
    }

    @Test
    void addCartCreatesItemWhenOptionIsNotInCart() {
        stubSellableOption(10L, 30000);
        when(cartRepository.findByUserIdAndOptionId(USER_ID, 10L)).thenReturn(Optional.empty());
        stubSaveReturnsArgument();

        CartItemResponse response = cartService.addCart(USER_ID, new CartCreateRequest(10L, 2));

        assertThat(response.optionId()).isEqualTo(10L);
        assertThat(response.quantity()).isEqualTo(2);
        assertThat(response.unitPrice()).isEqualTo(30000);
        assertThat(response.linePrice()).isEqualTo(60000);
    }

    @Test
    void addCartSumsQuantityWhenOptionIsAlreadyInCart() {
        stubSellableOption(10L, 30000);
        CartEntity existing = cart(5L, USER_ID, 10L, 2);
        when(cartRepository.findByUserIdAndOptionId(USER_ID, 10L)).thenReturn(Optional.of(existing));
        stubSaveReturnsArgument();

        CartItemResponse response = cartService.addCart(USER_ID, new CartCreateRequest(10L, 3));

        assertThat(response.cartId()).isEqualTo(5L);
        assertThat(response.quantity()).isEqualTo(5);
    }

    @Test
    void addCartSumsQuantityWithoutAnUpperBound() {
        stubSellableOption(10L, 30000);
        when(cartRepository.findByUserIdAndOptionId(USER_ID, 10L))
                .thenReturn(Optional.of(cart(5L, USER_ID, 10L, 98)));
        stubSaveReturnsArgument();

        CartItemResponse response = cartService.addCart(USER_ID, new CartCreateRequest(10L, 2));

        assertThat(response.quantity()).isEqualTo(100);
    }

    @Test
    void addCartRejectsQuantityThatOverflowsInteger() {
        stubSellableOption(10L, 30000);
        when(cartRepository.findByUserIdAndOptionId(USER_ID, 10L))
                .thenReturn(Optional.of(cart(5L, USER_ID, 10L, Integer.MAX_VALUE)));

        assertErrorCode(() -> cartService.addCart(USER_ID, new CartCreateRequest(10L, 1)),
                ErrorCode.INVALID_REQUEST);
        verify(cartRepository, never()).saveAndFlush(any());
    }

    @Test
    void addCartRejectsUnknownOption() {
        when(productOptionClient.findOptions(List.of(10L))).thenReturn(Map.of());

        assertErrorCode(() -> cartService.addCart(USER_ID, new CartCreateRequest(10L, 1)),
                ErrorCode.OPTION_NOT_FOUND);
    }

    @Test
    void addCartRejectsOptionThatIsNotOnSale() {
        when(productOptionClient.findOptions(List.of(10L))).thenReturn(Map.of(10L, option(10L, 30000, "SOLD_OUT")));

        assertErrorCode(() -> cartService.addCart(USER_ID, new CartCreateRequest(10L, 1)),
                ErrorCode.OPTION_NOT_AVAILABLE);
        verify(cartRepository, never()).saveAndFlush(any());
    }

    @Test
    void addCartRetriesAndSumsWhenConcurrentInsertHitsUniqueConstraint() {
        stubSellableOption(10L, 30000);
        CartEntity insertedByOtherRequest = cart(5L, USER_ID, 10L, 1);
        when(cartRepository.findByUserIdAndOptionId(USER_ID, 10L))
                .thenReturn(Optional.empty(), Optional.of(insertedByOtherRequest));
        when(cartRepository.saveAndFlush(any(CartEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(call -> call.getArgument(0));

        CartItemResponse response = cartService.addCart(USER_ID, new CartCreateRequest(10L, 1));

        assertThat(response.cartId()).isEqualTo(5L);
        assertThat(response.quantity()).isEqualTo(2);
    }

    @Test
    void updateCartChangesQuantityWhenOptionIsUnchanged() {
        stubSellableOption(10L, 30000);
        when(cartRepository.findByCartIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(cart(5L, USER_ID, 10L, 2)));
        stubSaveReturnsArgument();

        CartItemResponse response = cartService.updateCart(USER_ID, 5L, new CartUpdateRequest(10L, 7));

        assertThat(response.quantity()).isEqualTo(7);
        verify(cartRepository, never()).findByUserIdAndOptionId(any(), any());
    }

    @Test
    void updateCartMovesItemToNewOptionWhenNotInCart() {
        stubSellableOption(11L, 45000);
        when(cartRepository.findByCartIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(cart(5L, USER_ID, 10L, 2)));
        when(cartRepository.findByUserIdAndOptionId(USER_ID, 11L)).thenReturn(Optional.empty());
        stubSaveReturnsArgument();

        CartItemResponse response = cartService.updateCart(USER_ID, 5L, new CartUpdateRequest(11L, 3));

        assertThat(response.cartId()).isEqualTo(5L);
        assertThat(response.optionId()).isEqualTo(11L);
        assertThat(response.quantity()).isEqualTo(3);
        assertThat(response.unitPrice()).isEqualTo(45000);
    }

    @Test
    void updateCartMergesIntoItemThatAlreadyHasTheNewOption() {
        stubSellableOption(11L, 45000);
        CartEntity source = cart(5L, USER_ID, 10L, 2);
        CartEntity target = cart(6L, USER_ID, 11L, 4);
        when(cartRepository.findByCartIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(source));
        when(cartRepository.findByUserIdAndOptionId(USER_ID, 11L)).thenReturn(Optional.of(target));
        stubSaveReturnsArgument();

        CartItemResponse response = cartService.updateCart(USER_ID, 5L, new CartUpdateRequest(11L, 3));

        assertThat(response.cartId()).isEqualTo(6L);
        assertThat(response.quantity()).isEqualTo(7);
        verify(cartRepository).delete(source);
    }

    @Test
    void updateCartMergesQuantityWithoutAnUpperBound() {
        stubSellableOption(11L, 45000);
        CartEntity source = cart(5L, USER_ID, 10L, 2);
        CartEntity target = cart(6L, USER_ID, 11L, 99);
        when(cartRepository.findByCartIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(source));
        when(cartRepository.findByUserIdAndOptionId(USER_ID, 11L)).thenReturn(Optional.of(target));
        stubSaveReturnsArgument();

        CartItemResponse response = cartService.updateCart(USER_ID, 5L, new CartUpdateRequest(11L, 1));

        assertThat(response.quantity()).isEqualTo(100);
        verify(cartRepository).delete(source);
    }

    @Test
    void updateCartRejectsCartOfAnotherUserBeforeCheckingTheOption() {
        when(cartRepository.findByCartIdAndUserId(5L, USER_ID)).thenReturn(Optional.empty());

        assertErrorCode(() -> cartService.updateCart(USER_ID, 5L, new CartUpdateRequest(99L, 1)),
                ErrorCode.CART_NOT_FOUND);
        verify(productOptionClient, never()).findOptions(any());
    }

    @Test
    void updateCartRetriesAndMergesWhenChangingToAnOptionCreatedConcurrently() {
        stubSellableOption(11L, 45000);
        CartEntity source = cart(5L, USER_ID, 10L, 2);
        CartEntity reloaded = cart(5L, USER_ID, 10L, 2);
        CartEntity createdConcurrently = cart(6L, USER_ID, 11L, 4);
        when(cartRepository.findByCartIdAndUserId(5L, USER_ID))
                .thenReturn(Optional.of(source), Optional.of(source), Optional.of(reloaded));
        when(cartRepository.findByUserIdAndOptionId(USER_ID, 11L))
                .thenReturn(Optional.empty(), Optional.of(createdConcurrently));
        when(cartRepository.saveAndFlush(any(CartEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(call -> call.getArgument(0));

        CartItemResponse response = cartService.updateCart(USER_ID, 5L, new CartUpdateRequest(11L, 3));

        assertThat(response.cartId()).isEqualTo(6L);
        assertThat(response.quantity()).isEqualTo(7);
        verify(cartRepository).delete(reloaded);
    }

    @Test
    void removeCartDeletesTheUsersItem() {
        CartEntity existing = cart(5L, USER_ID, 10L, 2);
        when(cartRepository.findByCartIdAndUserId(5L, USER_ID)).thenReturn(Optional.of(existing));

        cartService.removeCart(USER_ID, 5L);

        verify(cartRepository).delete(existing);
    }

    @Test
    void removeCartRejectsMissingItem() {
        when(cartRepository.findByCartIdAndUserId(5L, USER_ID)).thenReturn(Optional.empty());

        assertErrorCode(() -> cartService.removeCart(USER_ID, 5L), ErrorCode.CART_NOT_FOUND);
    }

    private void stubSellableOption(Long optionId, int price) {
        when(productOptionClient.findOptions(List.of(optionId)))
                .thenReturn(Map.of(optionId, option(optionId, price, "ON_SALE")));
    }

    private void stubSaveReturnsArgument() {
        when(cartRepository.saveAndFlush(any(CartEntity.class))).thenAnswer(call -> call.getArgument(0));
    }
}
