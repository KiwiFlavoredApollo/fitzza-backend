package com.fitzza.community.votesupport;

// 댓글이 새로 저장되거나 내용이 바뀌어 지지 선택지를 (다시) 판정해야 할 때 발행한다.
public record CommentSupportRequested(Long commentId) {
}
