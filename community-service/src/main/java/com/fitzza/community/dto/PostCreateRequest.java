package com.fitzza.community.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fitzza.community.domain.Post;
import com.fitzza.community.domain.PostCategory;
import com.fitzza.community.domain.SharedType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// 공유 글이면 sharedType과 sharedId를 함께 보낸다(지금은 WORLDCUP + 월드컵 이력 ID만 받는다).
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
        @Positive(message = "공유 대상을 확인해주세요.") Long sharedId) {

    @JsonIgnore
    @AssertTrue(message = "공유 대상은 sharedType과 sharedId를 함께 보내야 합니다.")
    public boolean isSharedTargetComplete() {
        return (sharedType == null) == (sharedId == null);
    }
}
