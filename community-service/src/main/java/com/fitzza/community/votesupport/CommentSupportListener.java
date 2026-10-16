package com.fitzza.community.votesupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 댓글·선택지 저장이 확정된 뒤 별도 스레드에서 판정한다. 판정이 실패해도 댓글 작성·수정은 이미 성공한 상태다.
@Component
public class CommentSupportListener {

    private static final Logger log = LoggerFactory.getLogger(CommentSupportListener.class);

    private final CommentSupportService supportService;

    public CommentSupportListener(CommentSupportService supportService) {
        this.supportService = supportService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommentSaved(CommentSupportRequested event) {
        try {
            supportService.judgeComment(event.commentId());
        } catch (RuntimeException exception) {
            log.warn("댓글 지지 선택지 판정 실패 commentId={}: {}", event.commentId(), exception.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOptionsReplaced(VoteOptionsReplaced event) {
        try {
            supportService.judgePost(event.postId());
        } catch (RuntimeException exception) {
            log.warn("투표글 댓글 재판정 실패 postId={}: {}", event.postId(), exception.getMessage());
        }
    }
}
