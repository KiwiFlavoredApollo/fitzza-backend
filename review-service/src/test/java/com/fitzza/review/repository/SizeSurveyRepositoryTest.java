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
    void unsentFeedbackSkipsSentClaimedProductlessAndRecentSurveys() {
        Instant now = Instant.now();
        SizeSurvey unsent = save(500L, 30L);
        SizeSurvey sent = save(501L, 30L);
        SizeSurvey claimed = save(502L, 30L);
        SizeSurvey staleClaim = save(503L, 30L);
        save(504L, null);
        sizeSurveyRepository.markFeedbackSent(sent.getId(), now);
        sizeSurveyRepository.claimFeedback(claimed.getId(), now, now.minusSeconds(300));
        sizeSurveyRepository.claimFeedback(staleClaim.getId(), now.minusSeconds(600), now.minusSeconds(900));

        assertThat(sizeSurveyRepository.findUnsentFeedback(
                        now.plusSeconds(60), now.minusSeconds(300), PageRequest.of(0, 10)))
                .extracting(SizeSurvey::getId)
                .containsExactly(unsent.getId(), staleClaim.getId());
        assertThat(sizeSurveyRepository.findUnsentFeedback(
                        now.minusSeconds(60), now.minusSeconds(300), PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    void onlyOneCallerCanClaimASurveyUntilItIsReleasedOrExpires() {
        Instant now = Instant.now();
        Long id = save(500L, 30L).getId();

        int first = sizeSurveyRepository.claimFeedback(id, now, now.minusSeconds(300));
        int whileClaimed = sizeSurveyRepository.claimFeedback(id, now, now.minusSeconds(300));
        int afterExpiry = sizeSurveyRepository.claimFeedback(id, now.plusSeconds(400), now.plusSeconds(100));
        sizeSurveyRepository.releaseFeedback(id);
        int afterRelease = sizeSurveyRepository.claimFeedback(id, now, now.minusSeconds(300));

        assertThat(first).isEqualTo(1);
        assertThat(whileClaimed).isZero();
        assertThat(afterExpiry).isEqualTo(1);
        assertThat(afterRelease).isEqualTo(1);
    }

    @Test
    void aSentSurveyIsNeverClaimedAgain() {
        Instant now = Instant.now();
        Long id = save(500L, 30L).getId();
        sizeSurveyRepository.claimFeedback(id, now, now.minusSeconds(300));

        sizeSurveyRepository.markFeedbackSent(id, now);
        sizeSurveyRepository.releaseFeedback(id);

        SizeSurvey loaded = sizeSurveyRepository.findById(id).orElseThrow();
        assertThat(loaded.getFeedbackSentAt()).isNotNull();
        assertThat(loaded.getFeedbackClaimedAt()).isNull();
        assertThat(sizeSurveyRepository.claimFeedback(id, now.plusSeconds(900), now.plusSeconds(600))).isZero();
    }

    private SizeSurvey save(long orderItemId, Long productId) {
        return sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, productId, 3L, orderItemId, SizeFeedback.FIT));
    }
}
