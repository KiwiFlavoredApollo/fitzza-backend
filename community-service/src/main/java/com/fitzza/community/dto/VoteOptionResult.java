package com.fitzza.community.dto;

// ratio는 0~1 사이 값이다. 표가 하나도 없으면 0이다.
// supportCommentCount는 이 선택지를 추천한다고 판정된 댓글 수다(Jev 판정, 지운 댓글 제외).
public record VoteOptionResult(
        Long voteOptionId, String label, long count, double ratio, long supportCommentCount) {
}
