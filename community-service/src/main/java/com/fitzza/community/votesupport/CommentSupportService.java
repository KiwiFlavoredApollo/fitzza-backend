package com.fitzza.community.votesupport;

import com.fitzza.community.domain.Comment;
import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.VoteOption;
import com.fitzza.community.repository.CommentRepository;
import com.fitzza.community.repository.PostRepository;
import com.fitzza.community.repository.VoteOptionRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

// 투표글 댓글의 지지 선택지를 판정해 저장한다. Jev 호출은 트랜잭션 밖에서 하고, 저장만 짧게 한다.
// 판정에 실패하거나 확신이 낮으면 아무것도 저장하지 않아 supportedOptionId는 NULL로 남는다.
// 저장은 CommentSupportWriter가 글 행을 잠그고 선택지가 아직 그 글의 것인지 확인한 뒤 한다.
@Service
public class CommentSupportService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final VoteOptionRepository voteOptionRepository;
    private final VoteSupportJudge judge;
    private final CommentSupportWriter writer;

    public CommentSupportService(
            CommentRepository commentRepository,
            PostRepository postRepository,
            VoteOptionRepository voteOptionRepository,
            VoteSupportJudge judge,
            CommentSupportWriter writer) {
        this.commentRepository = commentRepository;
        this.postRepository = postRepository;
        this.voteOptionRepository = voteOptionRepository;
        this.judge = judge;
        this.writer = writer;
    }

    public void judgeComment(Long commentId) {
        Optional<Comment> comment = commentRepository.findById(commentId).filter(found -> !found.isDeleted());
        if (comment.isEmpty()) {
            return;
        }
        Optional<Post> post = votePost(comment.get().getPostId());
        if (post.isEmpty()) {
            return;
        }
        List<VoteOption> options = voteOptionRepository.findByPostIdOrderByDisplayOrderAsc(post.get().getId());
        judgeAndSave(post.get(), comment.get(), options);
    }

    // 선택지를 바꾼 글의 댓글 전부를 새 선택지 기준으로 다시 판정한다.
    public void judgePost(Long postId) {
        Optional<Post> post = votePost(postId);
        if (post.isEmpty()) {
            return;
        }
        List<VoteOption> options = voteOptionRepository.findByPostIdOrderByDisplayOrderAsc(postId);
        for (Comment comment : commentRepository.findByPostIdOrderByCreatedAtAscIdAsc(postId)) {
            if (!comment.isDeleted()) {
                judgeAndSave(post.get(), comment, options);
            }
        }
    }

    private void judgeAndSave(Post post, Comment comment, List<VoteOption> options) {
        judge.judge(post.getTitle(), comment.getContent(), options)
                .ifPresent(optionId -> writer.save(post.getId(), comment.getId(), comment.getVersion(), optionId));
    }

    private Optional<Post> votePost(Long postId) {
        return postRepository.findByIdAndDeletedAtIsNull(postId).filter(Post::isVote);
    }
}
