package com.fitzza.review.repository;

import com.fitzza.review.domain.SizeSurvey;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SizeSurveyRepository extends JpaRepository<SizeSurvey, Long> {

    boolean existsByUserIdAndOrderItemId(Long userId, Long orderItemId);

    List<SizeSurvey> findByUserIdAndOrderItemIdInOrderByOrderItemIdAsc(Long userId, Collection<Long> orderItemIds);
}
