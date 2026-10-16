package com.fitzza.user.security;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenCookie {

    public static final String NAME = "refreshToken";
    private final boolean secure;
    private final Duration maxAge;

    public RefreshTokenCookie(
            @Value("${auth.refresh-cookie.secure:false}") boolean secure,
            @Value("${jwt.refresh-expiration-seconds}") long expirationSeconds) {
        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException("Refresh cookie expiration must be greater than zero.");
        }
        this.secure = secure;
        this.maxAge = Duration.ofSeconds(expirationSeconds);
    }

    public String issue(String token) {
        return build(token, maxAge).toString();
    }

    public String clear() {
        return build("", Duration.ZERO).toString();
    }

    private ResponseCookie build(String value, Duration age) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/api")
                .maxAge(age)
                .build();
    }
}
