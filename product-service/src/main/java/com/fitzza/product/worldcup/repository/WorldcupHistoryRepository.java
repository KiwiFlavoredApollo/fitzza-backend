package com.fitzza.product.worldcup.repository;

import com.fitzza.product.worldcup.entity.WorldcupHistoryEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorldcupHistoryRepository extends JpaRepository<WorldcupHistoryEntity, Long> {

    @EntityGraph(attributePaths = {"winnerProduct", "winnerProduct.brand"})
    Optional<WorldcupHistoryEntity> findFirstByUserIdOrderByCompletedAtDescWorldcupHistoryIdDesc(Long userId);

    @EntityGraph(attributePaths = {"winnerProduct", "winnerProduct.brand"})
    Page<WorldcupHistoryEntity> findAllByUserId(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"winnerProduct", "winnerProduct.brand"})
    Optional<WorldcupHistoryEntity> findWithWinnerByWorldcupHistoryId(Long worldcupHistoryId);
}
