package com.fitzza.order.cart.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitzza.order.cart.entity.CartEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest(properties = "spring.cloud.config.enabled=false")
class CartRepositoryTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void saveDefaultsQuantityToOneWhenOmitted() {
        CartEntity saved = cartRepository.saveAndFlush(
                CartEntity.builder().userId(USER_ID).optionId(10L).build());
        entityManager.clear();

        CartEntity found = cartRepository.findById(saved.getCartId()).orElseThrow();

        assertThat(found.getQuantity()).isEqualTo(1);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void saveRejectsSameOptionForTheSameUser() {
        cartRepository.saveAndFlush(CartEntity.builder().userId(USER_ID).optionId(10L).quantity(1).build());

        assertThatThrownBy(() -> cartRepository.saveAndFlush(
                CartEntity.builder().userId(USER_ID).optionId(10L).quantity(2).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void saveAllowsSameOptionForDifferentUsers() {
        cartRepository.saveAndFlush(CartEntity.builder().userId(USER_ID).optionId(10L).quantity(1).build());

        CartEntity other = cartRepository.saveAndFlush(
                CartEntity.builder().userId(OTHER_USER_ID).optionId(10L).quantity(1).build());

        assertThat(other.getCartId()).isNotNull();
    }

    @Test
    void findAllByUserIdReturnsNewestFirst() {
        CartEntity first = persistCart(USER_ID, 10L);
        CartEntity second = persistCart(USER_ID, 11L);
        persistCart(OTHER_USER_ID, 12L);

        List<CartEntity> carts = cartRepository.findAllByUserIdOrderByCartIdDesc(USER_ID);

        assertThat(carts).extracting(CartEntity::getCartId).containsExactly(second.getCartId(), first.getCartId());
        assertThat(cartRepository.countByUserId(USER_ID)).isEqualTo(2);
    }

    @Test
    void findAllByCartIdInIgnoresCartsOfOtherUsers() {
        CartEntity mine = persistCart(USER_ID, 10L);
        CartEntity theirs = persistCart(OTHER_USER_ID, 11L);

        List<CartEntity> carts = cartRepository.findAllByCartIdInAndUserIdOrderByCartId(
                List.of(theirs.getCartId(), mine.getCartId()), USER_ID);

        assertThat(carts).extracting(CartEntity::getCartId).containsExactly(mine.getCartId());
    }

    @Test
    void findAllByCartIdInForUpdateLocksOwnedCartsInCartIdOrder() {
        CartEntity first = persistCart(USER_ID, 10L);
        CartEntity second = persistCart(USER_ID, 11L);
        persistCart(OTHER_USER_ID, 12L);

        List<CartEntity> carts = cartRepository.findAllByCartIdInAndUserIdForUpdate(
                List.of(second.getCartId(), first.getCartId()), USER_ID);

        assertThat(carts).extracting(CartEntity::getCartId).containsExactly(first.getCartId(), second.getCartId());
    }

    @Test
    void deleteByUserIdAndCartIdsRemovesOnlyTheUsersCarts() {
        CartEntity mine = persistCart(USER_ID, 10L);
        CartEntity theirs = persistCart(OTHER_USER_ID, 11L);

        int deleted = cartRepository.deleteByUserIdAndCartIds(USER_ID, List.of(mine.getCartId(), theirs.getCartId()));

        assertThat(deleted).isEqualTo(1);
        assertThat(cartRepository.findById(mine.getCartId())).isEmpty();
        assertThat(cartRepository.findById(theirs.getCartId())).isPresent();
    }

    @Test
    void findByUserIdAndOptionIdFindsTheMatchingCart() {
        CartEntity cart = persistCart(USER_ID, 10L);

        assertThat(cartRepository.findByUserIdAndOptionId(USER_ID, 10L)).get()
                .extracting(CartEntity::getCartId).isEqualTo(cart.getCartId());
        assertThat(cartRepository.findByUserIdAndOptionId(OTHER_USER_ID, 10L)).isEmpty();
        assertThat(cartRepository.findByCartIdAndUserId(cart.getCartId(), OTHER_USER_ID)).isEmpty();
    }

    private CartEntity persistCart(Long userId, Long optionId) {
        return cartRepository.saveAndFlush(CartEntity.builder().userId(userId).optionId(optionId).quantity(1).build());
    }
}
