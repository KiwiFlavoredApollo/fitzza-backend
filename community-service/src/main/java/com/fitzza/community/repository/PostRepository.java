package com.fitzza.community.repository;

import com.fitzza.community.domain.Post;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PostRepository extends JpaRepository<Post, Long>, JpaSpecificationExecutor<Post> {

    Optional<Post> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByIdAndDeletedAtIsNull(Long id);

    // 투표 접수와 조기 종료가 겹쳐도 종료 뒤에 표가 들어가지 않도록 글 행을 잠그고 읽는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Post p where p.id = :postId and p.deletedAt is null")
    Optional<Post> findVisibleForUpdate(@Param("postId") Long postId);

    // 마감 시각이 지났는데 아직 마감 처리되지 않은 투표글. 삭제된 글은 알릴 대상이 아니므로 뺀다.
    @Query("select p.id from Post p where p.postType = com.fitzza.community.domain.PostType.VOTE"
            + " and p.voteClosed = false and p.deletedAt is null and p.voteEndAt <= :now order by p.voteEndAt asc")
    List<Long> findVotePostIdsDueToClose(@Param("now") Instant now, Pageable pageable);

    // 마감했지만 작성자에게 아직 알리지 못한 투표글. 삭제된 글에는 알리지 않는다.
    // 아직 시도하지 않은 글을 먼저, 그다음 실패한 지 오래된 글 순으로 가져와 실패한 글이 한 페이지를 계속 차지하지 않게 한다.
    @Query("select p.id from Post p where p.voteCloseNotificationPending = true and p.deletedAt is null"
            + " order by p.voteCloseNotifyFailedAt asc nulls first, p.voteEndAt asc")
    List<Long> findPostIdsAwaitingVoteCloseNotification(Pageable pageable);

    // 알림을 보낸 뒤 대기 표시만 지운다. 버전을 올리지 않아 그사이 사용자의 글 수정과 부딪히지 않는다.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Post p set p.voteCloseNotificationPending = false where p.id = :postId")
    int markVoteCloseNotified(@Param("postId") Long postId);

    // 알림 전송이 실패한 시각을 남겨 다음 실행에서 순서를 뒤로 미룬다. 위와 같은 이유로 버전을 올리지 않는다.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Post p set p.voteCloseNotifyFailedAt = :failedAt where p.id = :postId")
    int recordVoteCloseNotifyFailure(@Param("postId") Long postId, @Param("failedAt") Instant failedAt);

    // 동시에 여러 명이 읽어도 조회수가 빠지지 않도록 DB에서 바로 더한다.
    @Modifying(clearAutomatically = true)
    @Query("update Post p set p.viewCount = p.viewCount + 1 where p.id = :postId and p.deletedAt is null")
    int incrementViewCount(@Param("postId") Long postId);
}
