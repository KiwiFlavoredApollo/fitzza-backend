package com.fitzza.community.notification;

// 마감 시각이 지나 서버가 투표를 마감했을 때 발행한다. 작성자가 직접 조기 종료한 경우에는 발행하지 않는다.
public record VoteClosedEvent(Long postId, Long postAuthorId, String title) {
}
