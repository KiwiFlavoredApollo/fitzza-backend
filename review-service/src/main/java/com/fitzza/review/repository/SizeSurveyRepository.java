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

    // 추천 서비스에 아직 보내지 못한 설문 중 보내는 중이 아니거나 보내는 중 표시가 만료된 것.
    // 상품 ID가 없는 예전 설문은 보낼 수 없어 뺀다.
    @Query("select s from SizeSurvey s "
            + "where s.feedbackSentAt is null and s.productId is not null and s.createdAt < :createdBefore "
            + "and (s.feedbackClaimedAt is null or s.feedbackClaimedAt < :claimExpiredBefore) "
            + "order by s.id asc")
    List<SizeSurvey> findUnsentFeedback(
            @Param("createdBefore") Instant createdBefore,
            @Param("claimExpiredBefore") Instant claimExpiredBefore,
            Pageable pageable);

    // 보내기 전에 '보내는 중'으로 표시해, 저장 직후 전송과 재시도가 겹쳐도 한 쪽만 보내게 한다. 1이면 이번 호출이 맡는다.
    // 이미 보냈거나 다른 쪽이 보내는 중(표시가 아직 만료되지 않음)이면 0이다.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update SizeSurvey s set s.feedbackClaimedAt = :claimedAt "
            + "where s.id = :surveyId and s.feedbackSentAt is null "
            + "and (s.feedbackClaimedAt is null or s.feedbackClaimedAt < :claimExpiredBefore)")
    int claimFeedback(
            @Param("surveyId") Long surveyId,
            @Param("claimedAt") Instant claimedAt,
            @Param("claimExpiredBefore") Instant claimExpiredBefore);

    // 추천 서비스가 받았으면 보낸 것으로 기록한다. 이후로는 다시 보내지 않는다.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update SizeSurvey s set s.feedbackSentAt = :sentAt, s.feedbackClaimedAt = null where s.id = :surveyId")
    int markFeedbackSent(@Param("surveyId") Long surveyId, @Param("sentAt") Instant sentAt);

    // 보내지 못했으면 '보내는 중' 표시를 지워 다음 재시도에서 바로 다시 보낸다.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update SizeSurvey s set s.feedbackClaimedAt = null where s.id = :surveyId and s.feedbackSentAt is null")
    int releaseFeedback(@Param("surveyId") Long surveyId);
}
