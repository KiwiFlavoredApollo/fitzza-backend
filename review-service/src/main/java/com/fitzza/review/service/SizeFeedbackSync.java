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
        List<SizeSurvey> unsent = sizeSurveyRepository.findUnsentFeedback(
                Instant.now().minus(RETRY_AFTER), PageRequest.of(0, BATCH_SIZE));
        for (SizeSurvey survey : unsent) {
            // 추천 서비스가 응답하지 않으면 나머지도 실패할 테니 다음 실행으로 미룬다.
            if (!sendOnce(survey)) {
                return;
            }
        }
    }

    // 다른 쪽이 이미 보냈거나 보내는 중이면 건너뛰고 true를 돌려준다. 보내지 못하면 표시를 되돌리고 false다.
    boolean sendOnce(SizeSurvey survey) {
        if (survey.getProductId() == null) {
            return true;
        }
        if (sizeSurveyRepository.claimFeedback(survey.getId(), Instant.now()) == 0) {
            return true;
        }
        try {
            sizeFeedbacks.send(new SizeFeedbackRequest(
                    survey.getUserId(), survey.getProductId(), survey.getOptionId(), survey.getSizeFeedback()));
            return true;
        } catch (RuntimeException exception) {
            // 보냄 표시를 해 둔 채로 남으면 다시는 보내지 않으므로, 어떤 실패든 표시를 되돌린다.
            sizeSurveyRepository.releaseFeedback(survey.getId());
            log.warn("추천 서비스가 사이즈 평가를 받지 못했습니다. 다음 재시도에서 다시 보냅니다. surveyId={}: {}",
                    survey.getId(), exception.getMessage());
            return false;
        }
    }
}
