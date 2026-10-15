package com.fitzza.review.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitzza.review.domain.SizeFeedback;
import com.fitzza.review.domain.SizeSurvey;
import com.fitzza.review.support.PostgresModeJpaTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

@PostgresModeJpaTest
class SizeSurveyRepositoryTest {

    @Autowired
    private SizeSurveyRepository sizeSurveyRepository;

    @Test
    void findsOnlyMySurveysForTheAskedOrderItems() {
        sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, 30L, 3L, 502L, SizeFeedback.BIG));
        sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, 30L, 3L, 500L, SizeFeedback.FIT));
        sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, 30L, 3L, 509L, SizeFeedback.SMALL));
        sizeSurveyRepository.saveAndFlush(SizeSurvey.of(8L, 30L, 3L, 501L, SizeFeedback.SMALL));

        List<SizeSurvey> mine =
                sizeSurveyRepository.findByUserIdAndOrderItemIdInOrderByOrderItemIdAsc(7L, List.of(500L, 501L, 502L));

        assertThat(mine).extracting(SizeSurvey::getOrderItemId).containsExactly(500L, 502L);
        assertThat(mine).extracting(SizeSurvey::getCreatedAt).doesNotContainNull();
    }

    @Test
    void oneSurveyPerUserAndOrderItem() {
        sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, 30L, 3L, 500L, SizeFeedback.FIT));

        assertThat(sizeSurveyRepository.existsByUserIdAndOrderItemId(7L, 500L)).isTrue();
        assertThatThrownBy(() -> sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, 30L, 3L, 500L, SizeFeedback.BIG)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unsentFeedbackSkipsSentSurveysSurveysWithoutAProductAndRecentOnes() {
        SizeSurvey unsent = sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, 30L, 3L, 500L, SizeFeedback.FIT));
        SizeSurvey sent = sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, 30L, 3L, 501L, SizeFeedback.BIG));
        sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, null, 3L, 502L, SizeFeedback.SMALL));
        sizeSurveyRepository.claimFeedback(sent.getId(), Instant.now());
        Instant later = Instant.now().plusSeconds(60);
        Instant earlier = Instant.now().minusSeconds(60);

        assertThat(sizeSurveyRepository.findUnsentFeedback(later, PageRequest.of(0, 10)))
                .extracting(SizeSurvey::getId)
                .containsExactly(unsent.getId());
        assertThat(sizeSurveyRepository.findUnsentFeedback(earlier, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void onlyOneCallerCanClaimASurveyUntilItIsReleased() {
        SizeSurvey survey = sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, 30L, 3L, 500L, SizeFeedback.FIT));

        int first = sizeSurveyRepository.claimFeedback(survey.getId(), Instant.now());
        int second = sizeSurveyRepository.claimFeedback(survey.getId(), Instant.now());
        sizeSurveyRepository.releaseFeedback(survey.getId());
        int afterRelease = sizeSurveyRepository.claimFeedback(survey.getId(), Instant.now());

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(afterRelease).isEqualTo(1);
        assertThat(sizeSurveyRepository.findById(survey.getId()).orElseThrow().getFeedbackSentAt()).isNotNull();
    }
}
