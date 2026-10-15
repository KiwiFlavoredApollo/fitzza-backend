package com.fitzza.review.repository;

import com.fitzza.review.domain.SizeSurvey;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SizeSurveyRepository extends JpaRepository<SizeSurvey, Long> {

    boolean existsByUserIdAndOrderItemId(Long userId, Long orderItemId);

    List<SizeSurvey> findByUserIdAndOrderItemIdInOrderByOrderItemIdAsc(Long userId, Collection<Long> orderItemIds);

    // 추천 서비스에 아직 보내지 못한 설문. 상품 ID가 없는 예전 설문은 보낼 수 없어 뺀다.
    @Query("select s from SizeSurvey s "
            + "where s.feedbackSentAt is null and s.productId is not null and s.createdAt < :createdBefore "
            + "order by s.id asc")
    List<SizeSurvey> findUnsentFeedback(@Param("createdBefore") Instant createdBefore, Pageable pageable);

    // 보내기 전에 먼저 '보냄'으로 표시해, 저장 직후 전송과 재시도가 겹쳐도 한 쪽만 보내게 한다. 1이면 이번 호출이 맡는다.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update SizeSurvey s set s.feedbackSentAt = :sentAt where s.id = :surveyId and s.feedbackSentAt is null")
    int claimFeedback(@Param("surveyId") Long surveyId, @Param("sentAt") Instant sentAt);

    // 보내지 못했으면 표시를 되돌려 다음 재시도에서 다시 보낸다.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update SizeSurvey s set s.feedbackSentAt = null where s.id = :surveyId")
    int releaseFeedback(@Param("surveyId") Long surveyId);
}
