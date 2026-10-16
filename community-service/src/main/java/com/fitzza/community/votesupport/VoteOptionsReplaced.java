package com.fitzza.community.votesupport;

// 투표 선택지를 통째로 바꿨을 때 발행한다. 그 글의 댓글을 새 선택지 기준으로 다시 판정한다.
public record VoteOptionsReplaced(Long postId) {
}
