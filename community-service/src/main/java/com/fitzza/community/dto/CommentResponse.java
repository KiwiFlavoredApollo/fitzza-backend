package com.fitzza.community.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

// 삭제된 댓글은 답글이 남아 있을 때만 내려가며, 그때 내용은 안내 문구로 바뀌고 author는 null이다.
// supportedOptionId는 투표글에서 이 댓글이 추천하는 선택지 ID다. 판정 전이거나 확신이 낮으면 null이다.
public record CommentResponse(
        Long commentId,
        String content,
        AuthorResponse author,
        long likeCount,
        boolean liked,
        @JsonProperty("isDeleted") boolean deleted,
        Long supportedOptionId,
        Instant createdAt,
        List<CommentResponse> replies) {
}
