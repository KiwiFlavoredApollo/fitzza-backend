package com.fitzza.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "comments")
public class Comment {

    public static final int CONTENT_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "comment_id")
    private Long id;

    @Column(name = "post_id", nullable = false, updatable = false)
    private Long postId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "parent_comment_id", updatable = false)
    private Long parentCommentId;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    // 투표글에서 이 댓글이 추천하는 선택지. Jev 판정 확신이 기준 이상일 때만 채우고, 아니면 NULL이다.
    // 저장 뒤 별도 스레드에서 채우므로 작성 직후에는 비어 있을 수 있다.
    @Column(name = "supported_option_id")
    private Long supportedOptionId;

    // 수정과 삭제가 겹치면 늦게 끝난 요청이 앞선 변경을 덮어쓰지 않고 실패한다.
    // 기본값은 이미 행이 있는 테이블에 이 컬럼을 추가할 때 필요하다.
    @Version
    @ColumnDefault("0")
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Comment() {
    }

    private Comment(Long postId, Long userId, String content, Long parentCommentId) {
        this.postId = postId;
        this.userId = userId;
        this.content = content.trim();
        this.parentCommentId = parentCommentId;
    }

    public static Comment create(Long postId, Long userId, String content) {
        return new Comment(postId, userId, content, null);
    }

    public static Comment replyTo(Comment parent, Long userId, String content) {
        return new Comment(parent.postId, userId, content, parent.id);
    }

    @PrePersist
    void markCreated() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void markUpdated() {
        this.updatedAt = Instant.now();
    }

    public boolean isWrittenBy(Long userId) {
        return this.userId.equals(userId);
    }

    public boolean isReply() {
        return parentCommentId != null;
    }

    // 내용이 바뀌면 이전 판정은 맞지 않으므로 지우고, 커밋 뒤 다시 판정한다.
    public void changeContent(String content) {
        this.content = content.trim();
        this.supportedOptionId = null;
    }

    // 답글이 달린 댓글을 지워도 답글은 남아야 하므로 행은 두고 표시만 바꾼다.
    public void delete() {
        this.deleted = true;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public Long getId() {
        return id;
    }

    public Long getPostId() {
        return postId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getContent() {
        return content;
    }

    public Long getParentCommentId() {
        return parentCommentId;
    }

    public long getVersion() {
        return version;
    }

    public Long getSupportedOptionId() {
        return supportedOptionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
