package com.fitzza.user.dto;

// 회원가입 409 응답에서 중복된 필드 하나와 그 원인을 담는다.
public record DuplicateField(String field, String reason) {

    public static final DuplicateField EMAIL = new DuplicateField("email", "이미 사용 중인 이메일입니다.");
    public static final DuplicateField NICKNAME = new DuplicateField("nickname", "이미 사용 중인 닉네임입니다.");
}
