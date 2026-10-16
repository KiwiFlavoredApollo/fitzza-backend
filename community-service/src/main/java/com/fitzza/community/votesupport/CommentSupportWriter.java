package com.fitzza.community.votesupport;

import com.fitzza.community.repository.CommentRepository;
import com.fitzza.community.repository.PostRepository;
import com.fitzza.community.repository.VoteOptionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 판정 결과 저장만 맡는다. Jev 호출은 이 트랜잭션 밖에서 끝난 뒤다.
// 선택지 교체(VoteService.replaceOptions)와 같은 글 행 잠금을 잡아 순서를 정한다. 판정하는 동안 선택지가 바뀌었으면
// 판정한 선택지는 이미 지워졌으므로 저장하지 않는다(새 선택지 기준 재판정이 따로 돈다).
@Component
public class CommentSupportWriter {

    private final PostRepository postRepository;
    private final VoteOptionRepository voteOptionRepository;
    private final CommentRepository commentRepository;

    public CommentSupportWriter(
            PostRepository postRepository,
            VoteOptionRepository voteOptionRepository,
            CommentRepository commentRepository) {
        this.postRepository = postRepository;
        this.voteOptionRepository = voteOptionRepository;
        this.commentRepository = commentRepository;
    }

    @Transactional
    public boolean save(Long postId, Long commentId, long version, Long optionId) {
        if (postRepository.findVisibleForUpdate(postId).isEmpty()) {
            return false;
        }
        if (!voteOptionRepository.existsByIdAndPostId(optionId, postId)) {
            return false;
        }
        return commentRepository.updateSupport(commentId, version, optionId) > 0;
    }
}
