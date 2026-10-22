package com.fitzza.community.notification;

// 다른 사람이 내 글에 댓글을 달았을 때만 발행한다. 본인 글에 본인이 단 댓글은 알리지 않는다.
public record CommentCreatedEvent(Long commentId, Long postId, Long postAuthorId, String content) {
}
