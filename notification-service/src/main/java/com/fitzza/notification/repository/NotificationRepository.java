package com.fitzza.notification.repository;

import com.fitzza.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, String> {

    // 같은 이벤트가 동시에 두 번 들어와도 오류 없이 한 건만 남도록 DB의 충돌 무시 삽입을 쓴다.
    // 네이티브 쿼리에 null을 넘기면 PostgreSQL이 타입을 정하지 못할 수 있어, 대상이 없으면 빈 문자열로 받아 NULL로 저장한다.
    @Modifying
    @Query(
            value = "insert into notification_logs "
                    + "(log_id, user_id, type, content, target_id, event_id, is_read, created_at) "
                    + "values (:logId, :userId, :type, :content, nullif(:targetId, ''), :eventId, false, now()) "
                    + "on conflict do nothing",
            nativeQuery = true)
    int insertIfAbsent(
            @Param("logId") String logId,
            @Param("userId") Long userId,
            @Param("type") String type,
            @Param("content") String content,
            @Param("targetId") String targetId,
            @Param("eventId") String eventId);

    Page<Notification> findByUserId(Long userId, Pageable pageable);

    long countByUserIdAndReadFalse(Long userId);
}
