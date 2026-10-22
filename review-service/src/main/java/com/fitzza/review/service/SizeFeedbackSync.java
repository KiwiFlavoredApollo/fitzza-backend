package com.fitzza.review.service;

import com.fitzza.review.client.SizeFeedbacks;
import com.fitzza.review.client.SizeFeedbacks.SizeFeedbackRequest;
import com.fitzza.review.domain.SizeSurvey;
import com.fitzza.review.repository.SizeSurveyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 사이즈 설문 결과를 추천 서비스(선호 실측 갱신)로 보낸다.
// 설문은 이미 저장됐으므로 보내기가 실패해도 설문 작성은 성공이고, 보내지 못한 설문은 주기적으로 다시 보낸다.
@Component
public class SizeFeedbackSync {

    // 한 번에 너무 많이 보내지 않도록 나눈다. 남은 설문은 다음 실행에서 이어서 보낸다.
    static final int BATCH_SIZE = 50;
    // 저장 직후 전송이 아직 진행 중일 수 있는 설문은 재시도에서 건너뛴다.
    static final Duration RETRY_AFTER = Duration.ofMinutes(1);
    // 보내는 중 표시의 유효 시간. 요청 시간 제한(수 초)보다 넉넉히 길게 두고, 지나면 다른 쪽이 다시 보낼 수 있다.
    static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(SizeFeedbackSync.class);

    private final SizeSurveyRepository sizeSurveyRepository;
    private final SizeFeedbacks sizeFeedbacks;

    public SizeFeedbackSync(SizeSurveyRepository sizeSurveyRepository, SizeFeedbacks sizeFeedbacks) {
        this.sizeSurveyRepository = sizeSurveyRepository;
        this.sizeFeedbacks = sizeFeedbacks;
    }

    // 설문 저장이 확정된 뒤 별도 스레드에서 보내, 설문 작성 응답이 추천 서비스를 기다리지 않게 한다.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSurveyCreated(SurveyCreatedEvent event) {
        if (!sizeFeedbacks.isEnabled()) {
            return;
        }
        try {
            sizeSurveyRepository.findById(event.surveyId()).ifPresent(this::sendOnce);
        } catch (RuntimeException exception) {
            log.warn("설문 결과를 추천 서비스로 보내지 못했습니다. 재시도에서 다시 보냅니다. surveyId={}: {}",
                    event.surveyId(), exception.getMessage());
        }
    }

    @Scheduled(
            fixedDelayString = "${fitzza.size-feedback.retry-delay-ms:300000}",
            initialDelayString = "${fitzza.size-feedback.retry-delay-ms:300000}")
    public void retryUnsent() {
        if (!sizeFeedbacks.isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        List<SizeSurvey> unsent = sizeSurveyRepository.findUnsentFeedback(
                now.minus(RETRY_AFTER), now.minus(CLAIM_TIMEOUT), PageRequest.of(0, BATCH_SIZE));
        for (SizeSurvey survey : unsent) {
            // 추천 서비스가 응답하지 않으면 나머지도 실패할 테니 다음 실행으로 미룬다.
            if (!sendOnce(survey)) {
                return;
            }
        }
    }

    // 다른 쪽이 이미 보냈거나 보내는 중이면 건너뛰고 true를 돌려준다. 보내지 못하면 false다.
    boolean sendOnce(SizeSurvey survey) {
        if (survey.getProductId() == null) {
            return true;
        }
        Instant now = Instant.now();
        if (sizeSurveyRepository.claimFeedback(survey.getId(), now, now.minus(CLAIM_TIMEOUT)) == 0) {
            return true;
        }
        try {
            sizeFeedbacks.send(new SizeFeedbackRequest(
                    survey.getUserId(), survey.getProductId(), survey.getOptionId(), survey.getSizeFeedback()));
        } catch (RuntimeException exception) {
            releaseClaim(survey.getId());
            log.warn("추천 서비스가 사이즈 평가를 받지 못했습니다. 다음 재시도에서 다시 보냅니다. surveyId={}: {}",
                    survey.getId(), exception.getMessage());
            return false;
        }
        markSent(survey.getId());
        return true;
    }

    // 표시를 지우지 못해도 CLAIM_TIMEOUT이 지나면 재시도 대상으로 돌아온다.
    private void releaseClaim(Long surveyId) {
        try {
            sizeSurveyRepository.releaseFeedback(surveyId);
        } catch (RuntimeException exception) {
            log.warn("보내는 중 표시를 지우지 못했습니다. 표시가 만료된 뒤 다시 보냅니다. surveyId={}: {}",
                    surveyId, exception.getMessage());
        }
    }

    // 추천 서비스는 이미 받았다. 기록에 실패하면 표시가 만료된 뒤 한 번 더 보내질 수 있다.
    private void markSent(Long surveyId) {
        try {
            sizeSurveyRepository.markFeedbackSent(surveyId, Instant.now());
        } catch (RuntimeException exception) {
            log.warn("보낸 기록을 남기지 못했습니다. 표시가 만료된 뒤 다시 보내질 수 있습니다. surveyId={}: {}",
                    surveyId, exception.getMessage());
        }
    }
}
