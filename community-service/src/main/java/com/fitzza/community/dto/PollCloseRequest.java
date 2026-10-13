package com.fitzza.community.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

// 투표 상태 변경은 종료만 허용한다. 닫힌 투표를 다시 여는 요청(closed=false)은 400이다.
public record PollCloseRequest(
        @NotNull(message = "closed 값을 보내주세요.")
                @AssertTrue(message = "종료된 투표는 다시 열 수 없어요.")
                Boolean closed) {
}
