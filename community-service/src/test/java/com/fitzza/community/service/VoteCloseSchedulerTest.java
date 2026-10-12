package com.fitzza.community.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fitzza.community.repository.PostRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

class VoteCloseSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private final PostRepository postRepository = mock(PostRepository.class);
    private final VoteClosingService voteClosingService = mock(VoteClosingService.class);
    private final VoteCloseScheduler scheduler = new VoteCloseScheduler(postRepository, voteClosingService);

    @Test
    void closesEachDueVoteInItsOwnStepAndKeepsGoingAfterAFailure() {
        when(postRepository.findVotePostIdsDueToClose(eq(NOW), any(Pageable.class))).thenReturn(List.of(1L, 2L, 3L));
        when(voteClosingService.closeIfDue(1L, NOW)).thenReturn(true);
        when(voteClosingService.closeIfDue(2L, NOW)).thenThrow(new PessimisticLockingFailureException("locked"));
        when(voteClosingService.closeIfDue(3L, NOW)).thenReturn(true);

        assertThat(scheduler.closeDueVotes(NOW)).isEqualTo(2);

        verify(voteClosingService).closeIfDue(3L, NOW);
    }

    @Test
    void looksAtABoundedNumberOfVotesPerRun() {
        when(postRepository.findVotePostIdsDueToClose(eq(NOW), any(Pageable.class))).thenReturn(List.of());

        assertThat(scheduler.closeDueVotes(NOW)).isZero();

        verify(postRepository).findVotePostIdsDueToClose(NOW, PageRequest.of(0, VoteCloseScheduler.BATCH_SIZE));
    }
}
