package com.fitzza.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

// event_id 유니크 제약이 같은 이벤트가 다시 전달돼도 알림이 한 번만 남도록 보장한다.
@Entity
@Table(
        name = "notification_logs",
        uniqueConstraints = @UniqueConstraint(name = "uk_notification_logs_event", columnNames = "event_id"),
        indexes = @Index(name = "idx_notification_logs_user_created", columnList = "user_id, created_at"))
public class Notification {

    public static final int EVENT_ID_MAX_LENGTH = 100;
    public static final int TARGET_ID_MAX_LENGTH = 100;
    public static final int CONTENT_MAX_LENGTH = 500;

    @Id
    @Column(name = "log_id", length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 20)
    private NotificationType type;

    @Column(name = "content", nullable = false, updatable = false, columnDefinition = "text")
    private String content;

    // 댓글·투표 알림은 게시글 ID, 피팅 알림은 피팅 작업 ID. 어느 화면으로 갈지는 type으로 구분한다.
    @Column(name = "target_id", updatable = false, length = TARGET_ID_MAX_LENGTH)
    private String targetId;

    @Column(name = "event_id", nullable = false, updatable = false, length = EVENT_ID_MAX_LENGTH)
    private String eventId;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public boolean belongsTo(Long userId) {
        return this.userId.equals(userId);
    }

    public void markRead() {
        this.read = true;
    }

    public String getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public NotificationType getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getEventId() {
        return eventId;
    }

    public boolean isRead() {
        return read;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
