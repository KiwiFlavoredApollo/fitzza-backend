package com.fitzza.user.dto;

import java.util.List;

// 공통 오류 응답에 중복된 필드 목록(errors)을 더한 회원가입 409 응답.
public record SignupConflictResponse(int status, String code, String message, List<DuplicateField> errors) {
}
