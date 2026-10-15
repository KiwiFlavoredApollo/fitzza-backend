package com.fitzza.community.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.PostCategory;
import com.fitzza.community.domain.PostType;
import com.fitzza.community.domain.SharedType;
import com.fitzza.community.domain.VoteOption;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

// postType을 보내지 않으면 일반 글이다. 투표 글은 postType=VOTE와 선택지(options) 2~4개를 함께 보낸다.
// 공유 글이면 sharedType(WORLDCUP·COMBO·FITTING)과 sharedId(월드컵 이력·추천 코디·가상 피팅 ID)를 함께 보낸다. 투표 글은 공유할 수 없다.
public record PostCreateRequest(
        @NotBlank(message = "제목을 입력해주세요.")
                @Size(max = Post.TITLE_MAX_LENGTH, message = "제목은 100자 이하로 입력해주세요.")
                String title,
        @NotBlank(message = "내용을 입력해주세요.")
                @Size(max = Post.CONTENT_MAX_LENGTH, message = "내용은 5000자 이하로 입력해주세요.")
                String content,
        @NotNull(message = "분류를 선택해주세요.") PostCategory category,
        @Size(max = Post.IMAGE_URL_MAX_LENGTH, message = "이미지 주소가 너무 깁니다.") String imageUrl,
        SharedType sharedType,
        @Positive(message = "공유 대상을 확인해주세요.") Long sharedId,
        PostType postType,
        @Size(
                        min = VoteOption.MIN_OPTIONS,
                        max = VoteOption.MAX_OPTIONS,
                        message = "투표 선택지는 2~4개여야 합니다.")
                List<@NotNull(message = "투표 선택지를 확인해주세요.") @Valid VoteOptionRequest> options) {

    @JsonIgnore
    public boolean isVote() {
        return postType == PostType.VOTE;
    }

    @JsonIgnore
    @AssertTrue(message = "공유 대상은 sharedType과 sharedId를 함께 보내야 합니다.")
    public boolean isSharedTargetComplete() {
        return (sharedType == null) == (sharedId == null);
    }

    @JsonIgnore
    @AssertTrue(message = "투표 글은 선택지를 2~4개 보내야 하고, 일반 글에는 선택지를 보낼 수 없습니다.")
    public boolean isOptionsMatchingPostType() {
        return isVote() == (options != null);
    }

    @JsonIgnore
    @AssertTrue(message = "투표 글은 공유 글로 쓸 수 없습니다.")
    public boolean isVoteNotShared() {
        return !isVote() || sharedType == null;
    }
}
