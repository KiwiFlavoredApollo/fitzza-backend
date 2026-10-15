package com.fitzza.notification.repository;

import com.fitzza.notification.domain.DeviceToken;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, String> {

    // 같은 토큰이 동시에 등록돼도 오류 없이 한 행만 남도록 충돌 무시 삽입을 쓴다. 주인은 assignToUser로 맞춘다.
    @Modifying
    @Query(
            value = "insert into device_tokens (token, user_id, updated_at) values (:token, :userId, now()) "
                    + "on conflict do nothing",
            nativeQuery = true)
    int insertIfAbsent(@Param("token") String token, @Param("userId") Long userId);

    @Modifying
    @Query(
            value = "update device_tokens set user_id = :userId, updated_at = now() where token = :token",
            nativeQuery = true)
    int assignToUser(@Param("token") String token, @Param("userId") Long userId);

    @Query("select t.token from DeviceToken t where t.userId = :userId")
    List<String> findTokensByUserId(@Param("userId") Long userId);

    // 다른 사람의 토큰은 지우지 못하도록 주인까지 조건에 넣는다.
    @Modifying
    @Query("delete from DeviceToken t where t.token = :token and t.userId = :userId")
    int deleteOwnedToken(@Param("token") String token, @Param("userId") Long userId);

    @Modifying
    @Query("delete from DeviceToken t where t.token in :tokens")
    int deleteByTokenIn(@Param("tokens") Collection<String> tokens);
}
