package com.fitzza.review.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitzza.review.domain.SizeFeedback;
import com.fitzza.review.domain.SizeSurvey;
import com.fitzza.review.support.PostgresModeJpaTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@PostgresModeJpaTest
class SizeSurveyRepositoryTest {

    @Autowired
    private SizeSurveyRepository sizeSurveyRepository;

    @Test
    void findsOnlyMySurveysForTheAskedOrderItems() {
        sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, 3L, 502L, SizeFeedback.BIG));
        sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, 3L, 500L, SizeFeedback.FIT));
        sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, 3L, 509L, SizeFeedback.SMALL));
        sizeSurveyRepository.saveAndFlush(SizeSurvey.of(8L, 3L, 501L, SizeFeedback.SMALL));

        List<SizeSurvey> mine =
                sizeSurveyRepository.findByUserIdAndOrderItemIdInOrderByOrderItemIdAsc(7L, List.of(500L, 501L, 502L));

        assertThat(mine).extracting(SizeSurvey::getOrderItemId).containsExactly(500L, 502L);
        assertThat(mine).extracting(SizeSurvey::getCreatedAt).doesNotContainNull();
    }

    @Test
    void oneSurveyPerUserAndOrderItem() {
        sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, 3L, 500L, SizeFeedback.FIT));

        assertThat(sizeSurveyRepository.existsByUserIdAndOrderItemId(7L, 500L)).isTrue();
        assertThatThrownBy(() -> sizeSurveyRepository.saveAndFlush(SizeSurvey.of(7L, 3L, 500L, SizeFeedback.BIG)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
