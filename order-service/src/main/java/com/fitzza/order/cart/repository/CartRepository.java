package com.fitzza.order.cart.repository;

import com.fitzza.order.cart.entity.CartEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface CartRepository extends JpaRepository<CartEntity, Long> {

    List<CartEntity> findAllByUserIdOrderByCartIdDesc(Long userId);

    Optional<CartEntity> findByCartIdAndUserId(Long cartId, Long userId);

    // 같은 옵션을 동시에 담을 때 읽은 수량을 덮어쓰지 않도록 행을 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CartEntity> findByUserIdAndOptionId(Long userId, Long optionId);
}
