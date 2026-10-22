package com.fitzza.community.votesupport;

import com.fitzza.community.domain.VoteOption;
import java.util.List;
import java.util.Optional;

// 투표글 댓글이 어느 선택지를 추천하는지 고른다. 확신할 수 없거나 판정할 수 없으면 빈 값이다.
public interface VoteSupportJudge {

    Optional<Long> judge(String postTitle, String comment, List<VoteOption> options);
}
