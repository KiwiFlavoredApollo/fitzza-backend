package com.fitzza.community.service;

import com.fitzza.community.domain.Post;
import com.fitzza.community.notification.VoteClosedEvent;
import com.fitzza.community.repository.PostRepository;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoteClosingService {

    private final PostRepository postRepository;
    private final ApplicationEventPublisher eventPublisher;

    public VoteClosingService(PostRepository postRepository, ApplicationEventPublisher eventPublisher) {
        this.postRepository = postRepository;
        this.eventPublisher = eventPublisher;
    }

    // 글 행을 잠그고 다시 확인한 뒤 마감한다. 서버가 여러 대여도 한 서버만 마감하고 알림 이벤트를 낸다.
    // 그사이 작성자가 조기 종료했거나 글이 지워졌으면 아무것도 하지 않는다.
    @Transactional
    public boolean closeIfDue(Long postId, Instant now) {
        Post post = postRepository.findVisibleForUpdate(postId).orElse(null);
        if (post == null || !post.isVote() || post.isVoteClosed() || !post.isVoteClosedAt(now)) {
            return false;
        }
        post.closeVote();
        eventPublisher.publishEvent(new VoteClosedEvent(post.getId(), post.getUserId(), post.getTitle()));
        return true;
    }
}
