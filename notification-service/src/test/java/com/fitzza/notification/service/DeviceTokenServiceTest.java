package com.fitzza.notification.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fitzza.notification.repository.DeviceTokenRepository;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DeviceTokenServiceTest {

    private static final Long USER_ID = 7L;

    private final DeviceTokenRepository deviceTokenRepository = mock(DeviceTokenRepository.class);
    private final DeviceTokenService deviceTokenService = new DeviceTokenService(deviceTokenRepository);

    @Test
    void registerStoresTheTrimmedTokenAndMakesTheUserItsOwner() {
        deviceTokenService.register(USER_ID, " token-a ");

        InOrder order = inOrder(deviceTokenRepository);
        order.verify(deviceTokenRepository).insertIfAbsent("token-a", USER_ID);
        order.verify(deviceTokenRepository).assignToUser("token-a", USER_ID);
    }

    @Test
    void unregisterOnlyDeletesTheUsersOwnToken() {
        deviceTokenService.unregister(USER_ID, " token-a ");

        verify(deviceTokenRepository).deleteOwnedToken("token-a", USER_ID);
    }

    @Test
    void removeTokensSkipsTheDatabaseWhenThereIsNothingToRemove() {
        deviceTokenService.removeTokens(Set.of());

        verify(deviceTokenRepository, never()).deleteByTokenIn(any());
    }

    @Test
    void removeTokensDeletesTheGivenTokens() {
        deviceTokenService.removeTokens(Set.of("token-a"));

        verify(deviceTokenRepository).deleteByTokenIn(Set.of("token-a"));
    }
}
