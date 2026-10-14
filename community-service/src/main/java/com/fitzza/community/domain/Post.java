package com.fitzza.community.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "posts")
public class Post {

    public static final int TITLE_MAX_LENGTH = 100;
    public static final int CONTENT_MAX_LENGTH = 5000;
    public static final int IMAGE_URL_MAX_LENGTH = 500;
    public static final Duration VOTE_DURATION = Duration.ofHours(6);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "post_id")
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "post_type", nullable = false, length = 20)
    private PostType postType;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private PostCategory category;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "image_url", length = IMAGE_URL_MAX_LENGTH)
    private String imageUrl;

    @Column(name = "view_count", nullable = false)
    private long viewCount;

    // 공유 글에만 값이 있다. 공유 대상과 공유 당시 모습(스냅샷)을 함께 저장한다.
    @Enumerated(EnumType.STRING)
    @Column(name = "shared_type", length = 20, updatable = false)
    private SharedType sharedType;

    @Column(name = "shared_id", updatable = false)
    private Long sharedId;

    @Embedded
    private SharedSnapshot sharedSnapshot;

    // 투표글에만 값이 있다. 일반 글은 NULL이다.
    @Column(name = "vote_end_at")
    private Instant voteEndAt;

    // 기본값은 이미 행이 있는 테이블에 이 컬럼을 추가할 때 필요하다.
    @ColumnDefault("false")
    @Column(name = "vote_closed", nullable = false)
    private boolean voteClosed;

    // 서버가 마감 시각에 마감했지만 작성자에게 아직 알리지 못한 투표. 알림이 성공할 때까지 남아 재시도된다.
    // 작성자가 조기 종료한 투표와 일반 글은 false다. 기본값은 이미 행이 있는 테이블에 컬럼을 추가할 때 필요하다.
    @ColumnDefault("false")
    @Column(name = "vote_close_notification_pending", nullable = false)
    private boolean voteCloseNotificationPending;

    // 마감 알림을 마지막으로 보내 보고 실패한 시각. 재시도 순서를 돌려, 계속 실패하는 글이 다른 글을 막지 않게 한다.
    @Column(name = "vote_close_notify_failed_at")
    private Instant voteCloseNotifyFailedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

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

    protected Post() {
    }

    private Post(Long userId, String title, String content, PostCategory category, String imageUrl) {
        this.userId = userId;
        this.postType = PostType.NORMAL;
        this.title = title.trim();
        this.content = content.trim();
        this.category = category;
        this.imageUrl = blankToNull(imageUrl);
    }

    public static Post createNormal(
            Long userId, String title, String content, PostCategory category, String imageUrl) {
        return new Post(userId, title, content, category, imageUrl);
    }

    // 마감 시각은 서버가 정한다. 클라이언트가 보낸 값은 받지 않는다.
    public static Post createVote(Long userId, String title, String content, PostCategory category, Instant now) {
        Post post = new Post(userId, title, content, category, null);
        post.postType = PostType.VOTE;
        post.voteEndAt = now.plus(VOTE_DURATION);
        return post;
    }

    // 공유 대상은 글을 쓸 때만 정할 수 있다(수정으로 바꿀 수 없음).
    public void attachShared(SharedType type, Long id, SharedSnapshot snapshot) {
        this.sharedType = type;
        this.sharedId = id;
        this.sharedSnapshot = snapshot;
    }

    public boolean isShared() {
        return sharedType != null;
    }

    public SharedType getSharedType() {
        return sharedType;
    }

    public Long getSharedId() {
        return sharedId;
    }

    public SharedSnapshot getSharedSnapshot() {
        return sharedSnapshot;
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

    public void changeTitle(String title) {
        this.title = title.trim();
    }

    public void changeContent(String content) {
        this.content = content.trim();
    }

    public void changeCategory(PostCategory category) {
        this.category = category;
    }

    // null이나 빈 문자열이 오면 첨부 이미지를 뗀다.
    public void changeImageUrl(String imageUrl) {
        this.imageUrl = blankToNull(imageUrl);
    }

    // 댓글과 알림이 글을 계속 가리킬 수 있도록 행은 남기고 삭제 시각만 기록한다.
    public void delete() {
        this.deletedAt = Instant.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isVote() {
        return postType == PostType.VOTE;
    }

    // 작성자가 일찍 끝냈거나 마감 시각이 지났으면 더 투표할 수 없다.
    public boolean isVoteClosedAt(Instant now) {
        return voteClosed || (voteEndAt != null && !now.isBefore(voteEndAt));
    }

    // 작성자가 조기 종료했거나 서버가 마감 처리했는지. 마감 시각이 지났는지는 isVoteClosedAt으로 본다.
    public boolean isVoteClosed() {
        return voteClosed;
    }

    // 마감 시각이 지나 서버가 마감한다. 마감과 알림 대기 표시를 같은 트랜잭션에 저장해 알림이 사라지지 않게 한다.
    public void closeVoteAtDeadline() {
        this.voteClosed = true;
        this.voteCloseNotificationPending = true;
    }

    public boolean isVoteCloseNotificationPending() {
        return voteCloseNotificationPending;
    }

    public void closeVote() {
        this.voteClosed = true;
    }

    public Instant getVoteEndAt() {
        return voteEndAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public PostType getPostType() {
        return postType;
    }

    public PostCategory getCategory() {
        return category;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public long getViewCount() {
        return viewCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
