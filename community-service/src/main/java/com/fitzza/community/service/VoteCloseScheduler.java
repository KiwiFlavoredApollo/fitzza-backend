package com.fitzza.community.service;

import com.fitzza.community.notification.VoteCloseNotifier;
import com.fitzza.community.repository.PostRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 마감 시각이 지난 투표를 마감 상태로 바꾸고, 마감했지만 아직 알리지 못한 투표의 작성자에게 알린다.
// 투표 화면은 마감 시각만으로도 마감을 판단하므로, 이 작업이 조금 늦어도 투표가 더 받아지지는 않는다.
@Component
public class VoteCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(VoteCloseScheduler.class);
    // 한 번에 너무 많은 글을 다루지 않도록 나눠서 처리한다. 남은 글은 다음 실행에서 이어서 처리한다.
    static final int BATCH_SIZE = 100;

    private final PostRepository postRepository;
    private final VoteClosingService voteClosingService;
    private final VoteCloseNotifier voteCloseNotifier;

    public VoteCloseScheduler(
            PostRepository postRepository, VoteClosingService voteClosingService, VoteCloseNotifier voteCloseNotifier) {
        this.postRepository = postRepository;
        this.voteClosingService = voteClosingService;
        this.voteCloseNotifier = voteCloseNotifier;
    }

    @Scheduled(fixedDelayString = "${fitzza.vote.close-check-delay-ms:60000}")
    public void run() {
        closeDueVotes(Instant.now());
        notifyClosedVotes();
    }

    int closeDueVotes(Instant now) {
        List<Long> dueIds = postRepository.findVotePostIdsDueToClose(now, PageRequest.of(0, BATCH_SIZE));
        int closed = 0;
        for (Long postId : dueIds) {
            // 글마다 트랜잭션을 나눠, 한 글에서 실패해도 나머지 글은 마감한다.
            try {
                if (voteClosingService.closeIfDue(postId, now)) {
                    closed++;
                }
            } catch (DataAccessException exception) {
                log.warn("투표 마감 처리 실패, 다음 실행에서 다시 시도합니다. postId={}: {}", postId, exception.getMessage());
            }
        }
        return closed;
    }

    // 방금 마감한 투표와 이전 실행에서 알림이 실패한 투표를 함께 보낸다.
    int notifyClosedVotes() {
        List<Long> pendingIds = postRepository.findPostIdsAwaitingVoteCloseNotification(PageRequest.of(0, BATCH_SIZE));
        int notified = 0;
        for (Long postId : pendingIds) {
            try {
                if (voteCloseNotifier.notifyAuthor(postId)) {
                    notified++;
                }
            } catch (DataAccessException exception) {
                log.warn("투표 마감 알림 처리 실패, 다음 실행에서 다시 시도합니다. postId={}: {}", postId, exception.getMessage());
            }
        }
        return notified;
    }
}
