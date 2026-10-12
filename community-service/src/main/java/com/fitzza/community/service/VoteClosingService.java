package com.fitzza.community.service;

import com.fitzza.community.domain.Post;
import com.fitzza.community.repository.PostRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoteClosingService {

    private final PostRepository postRepository;

    public VoteClosingService(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    // 글 행을 잠그고 다시 확인한 뒤 마감한다. 서버가 여러 대여도 한 서버만 마감한다.
    // 알림은 여기서 보내지 않고 대기 표시만 남긴다. 보내는 일은 VoteCloseNotifier가 성공할 때까지 맡는다.
    // 그사이 작성자가 조기 종료했거나 글이 지워졌으면 아무것도 하지 않는다.
    @Transactional
    public boolean closeIfDue(Long postId, Instant now) {
        Post post = postRepository.findVisibleForUpdate(postId).orElse(null);
        if (post == null || !post.isVote() || post.isVoteClosed() || !post.isVoteClosedAt(now)) {
            return false;
        }
        post.closeVoteAtDeadline();
        return true;
    }
}
