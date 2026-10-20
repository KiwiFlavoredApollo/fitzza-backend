package com.fitzza.community.dto;

import com.fitzza.community.domain.VoteItemType;
import com.fitzza.community.domain.VoteOption;
import com.fitzza.community.domain.VoteOptionSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// 선택지는 찜한 상품이나 내가 받은 AI 추천만 고를 수 있고, 상품·코디의 숫자 ID를 itemId로 보낸다. 사용자가 직접 올린 사진은 받지 않는다.
public record VoteOptionRequest(
        @NotNull(message = "선택지 출처를 골라주세요.") VoteOptionSource source,
        @NotNull(message = "선택지 종류를 골라주세요.") VoteItemType itemType,
        @Positive(message = "선택지 대상을 확인해주세요.") Long itemId,
        @NotBlank(message = "선택지 이미지를 넣어주세요.")
                @Size(max = VoteOption.IMAGE_URL_MAX_LENGTH, message = "이미지 주소가 너무 깁니다.")
                String imageUrl) {
}
