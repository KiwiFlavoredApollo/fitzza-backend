package com.fitzza.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

// FCM 기기 토큰. 토큰은 기기(브라우저)마다 하나라 기본 키로 두고, 한 사용자는 여러 기기를 가질 수 있다.
// 같은 기기에서 다른 계정으로 로그인하면 토큰의 주인이 바뀐다.
@Entity
@Table(name = "device_tokens", indexes = @Index(name = "idx_device_tokens_user", columnList = "user_id"))
public class DeviceToken {

    public static final int TOKEN_MAX_LENGTH = 512;

    @Id
    @Column(name = "token", length = TOKEN_MAX_LENGTH)
    private String token;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DeviceToken() {
    }

    public String getToken() {
        return token;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
