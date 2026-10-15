package com.fitzza.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fitzza.review.client.SizeFeedbacks;
import com.fitzza.review.client.SizeFeedbacks.SizeFeedbackRequest;
import com.fitzza.review.domain.SizeFeedback;
import com.fitzza.review.domain.SizeSurvey;
import com.fitzza.review.repository.SizeSurveyRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

class SizeFeedbackSyncTest {

    private final SizeSurveyRepository sizeSurveyRepository = mock(SizeSurveyRepository.class);
    private final SizeFeedbacks sizeFeedbacks = mock(SizeFeedbacks.class);
    private final SizeFeedbackSync sync = new SizeFeedbackSync(sizeSurveyRepository, sizeFeedbacks);

    @BeforeEach
    void setUp() {
        when(sizeFeedbacks.isEnabled()).thenReturn(true);
        when(sizeSurveyRepository.claimFeedback(anyLong(), any(Instant.class), any(Instant.class))).thenReturn(1);
    }

    @Test
    void aNewSurveyIsSentWithTheOrderedProductAndOption() {
        when(sizeSurveyRepository.findById(21L)).thenReturn(Optional.of(survey(21L, 30L)));

        sync.onSurveyCreated(new SurveyCreatedEvent(21L));

        verify(sizeSurveyRepository).claimFeedback(eq(21L), any(Instant.class), any(Instant.class));
        verify(sizeFeedbacks).send(new SizeFeedbackRequest(7L, 30L, 3L, SizeFeedback.SMALL));
        verify(sizeSurveyRepository).markFeedbackSent(eq(21L), any(Instant.class));
        verify(sizeSurveyRepository, never()).releaseFeedback(anyLong());
    }

    @Test
    void aFailedSendIsReleasedForTheRetryAndDoesNotEscape() {
        when(sizeSurveyRepository.findById(21L)).thenReturn(Optional.of(survey(21L, 30L)));
        doThrow(new ResourceAccessException("recommendation down")).when(sizeFeedbacks).send(any());

        assertThatCode(() -> sync.onSurveyCreated(new SurveyCreatedEvent(21L))).doesNotThrowAnyException();

        verify(sizeSurveyRepository).releaseFeedback(21L);
        verify(sizeSurveyRepository, never()).markFeedbackSent(anyLong(), any(Instant.class));
    }

    // 표시를 지우지 못해도 예외가 퍼지지 않고, 표시는 만료된 뒤 재시도 대상으로 돌아온다.
    @Test
    void aFailedReleaseDoesNotEscape() {
        when(sizeSurveyRepository.findById(21L)).thenReturn(Optional.of(survey(21L, 30L)));
        doThrow(new ResourceAccessException("recommendation down")).when(sizeFeedbacks).send(any());
        when(sizeSurveyRepository.releaseFeedback(21L)).thenThrow(new IllegalStateException("db down"));

        assertThat(sync.sendOnce(survey(21L, 30L))).isFalse();
    }

    @Test
    void aFailedSentRecordStillCountsAsSent() {
        when(sizeSurveyRepository.markFeedbackSent(eq(21L), any(Instant.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(sync.sendOnce(survey(21L, 30L))).isTrue();
        verify(sizeSurveyRepository, never()).releaseFeedback(anyLong());
    }

    @Test
    void aSurveyAlreadyClaimedElsewhereIsNotSentAgain() {
        when(sizeSurveyRepository.claimFeedback(eq(21L), any(Instant.class), any(Instant.class))).thenReturn(0);

        assertThat(sync.sendOnce(survey(21L, 30L))).isTrue();

        verify(sizeFeedbacks, never()).send(any());
    }

    @Test
    void aSurveyWithoutAProductIsSkipped() {
        assertThat(sync.sendOnce(survey(21L, null))).isTrue();

        verify(sizeSurveyRepository, never()).claimFeedback(anyLong(), any(Instant.class), any(Instant.class));
        verify(sizeFeedbacks, never()).send(any());
    }

    @Test
    void nothingIsSentOrRetriedUntilTheRecommendationServiceIsConfigured() {
        when(sizeFeedbacks.isEnabled()).thenReturn(false);

        sync.onSurveyCreated(new SurveyCreatedEvent(21L));
        sync.retryUnsent();

        verifyNoInteractions(sizeSurveyRepository);
        verify(sizeFeedbacks, never()).send(any());
    }

    @Test
    void retrySendsUnsentSurveysAndStopsAtTheFirstFailure() {
        when(sizeSurveyRepository.findUnsentFeedback(any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(survey(21L, 30L), survey(22L, 31L), survey(23L, 32L)));
        doThrow(new ResourceAccessException("recommendation down"))
                .when(sizeFeedbacks)
                .send(new SizeFeedbackRequest(7L, 31L, 3L, SizeFeedback.SMALL));

        sync.retryUnsent();

        verify(sizeFeedbacks, times(2)).send(any());
        verify(sizeSurveyRepository).markFeedbackSent(eq(21L), any(Instant.class));
        verify(sizeSurveyRepository).releaseFeedback(22L);
        verify(sizeSurveyRepository, never()).claimFeedback(eq(23L), any(Instant.class), any(Instant.class));
    }

    private static SizeSurvey survey(Long id, Long productId) {
        SizeSurvey survey = SizeSurvey.of(7L, productId, 3L, 500L + id, SizeFeedback.SMALL);
        ReflectionTestUtils.setField(survey, "id", id);
        return survey;
    }
}
