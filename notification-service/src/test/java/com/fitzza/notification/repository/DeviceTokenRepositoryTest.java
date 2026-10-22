package com.fitzza.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitzza.notification.support.PostgresModeJpaTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@PostgresModeJpaTest
class DeviceTokenRepositoryTest {

    private static final Long USER_ID = 7L;
    private static final Long OTHER_ID = 8L;

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Test
    void theSameTokenIsStoredOnlyOnce() {
        int first = deviceTokenRepository.insertIfAbsent("token-a", USER_ID);
        int second = deviceTokenRepository.insertIfAbsent("token-a", USER_ID);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(deviceTokenRepository.findTokensByUserId(USER_ID)).containsExactly("token-a");
    }

    @Test
    void oneUserCanHaveSeveralDevices() {
        register("token-a", USER_ID);
        register("token-b", USER_ID);
        register("token-c", OTHER_ID);

        assertThat(deviceTokenRepository.findTokensByUserId(USER_ID)).containsExactlyInAnyOrder("token-a", "token-b");
    }

    @Test
    void registeringAnotherUsersTokenMovesItToTheNewUser() {
        register("token-a", USER_ID);

        register("token-a", OTHER_ID);

        assertThat(deviceTokenRepository.findTokensByUserId(USER_ID)).isEmpty();
        assertThat(deviceTokenRepository.findTokensByUserId(OTHER_ID)).containsExactly("token-a");
    }

    @Test
    void onlyTheOwnerCanDeleteAToken() {
        register("token-a", USER_ID);

        int byOther = deviceTokenRepository.deleteOwnedToken("token-a", OTHER_ID);
        int byOwner = deviceTokenRepository.deleteOwnedToken("token-a", USER_ID);

        assertThat(byOther).isZero();
        assertThat(byOwner).isEqualTo(1);
        assertThat(deviceTokenRepository.findTokensByUserId(USER_ID)).isEmpty();
    }

    @Test
    void staleTokensAreDeletedWhoeverOwnsThem() {
        register("token-a", USER_ID);
        register("token-b", USER_ID);
        register("token-c", OTHER_ID);

        deviceTokenRepository.deleteByTokenIn(List.of("token-a", "token-c"));

        assertThat(deviceTokenRepository.findTokensByUserId(USER_ID)).containsExactly("token-b");
        assertThat(deviceTokenRepository.findTokensByUserId(OTHER_ID)).isEmpty();
    }

    private void register(String token, Long userId) {
        deviceTokenRepository.insertIfAbsent(token, userId);
        deviceTokenRepository.assignToUser(token, userId);
    }
}
