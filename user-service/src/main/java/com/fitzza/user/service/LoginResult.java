package com.fitzza.user.service;

public record LoginResult(String accessToken, String refreshToken, Long userId, String nickname) {
}
