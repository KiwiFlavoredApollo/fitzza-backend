package com.fitzza.notification.service;

import com.fitzza.notification.repository.DeviceTokenRepository;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;

    public DeviceTokenService(DeviceTokenRepository deviceTokenRepository) {
        this.deviceTokenRepository = deviceTokenRepository;
    }

    // 같은 토큰을 다시 등록해도 결과는 같다. 다른 계정이 쓰던 토큰이면 이 사용자에게 옮긴다.
    @Transactional
    public void register(Long userId, String token) {
        String trimmed = token.trim();
        deviceTokenRepository.insertIfAbsent(trimmed, userId);
        deviceTokenRepository.assignToUser(trimmed, userId);
    }

    // 없는 토큰이나 다른 사람의 토큰을 지우려 해도 오류 없이 넘어간다.
    @Transactional
    public void unregister(Long userId, String token) {
        deviceTokenRepository.deleteOwnedToken(token.trim(), userId);
    }

    @Transactional(readOnly = true)
    public List<String> findTokens(Long userId) {
        return deviceTokenRepository.findTokensByUserId(userId);
    }

    // FCM이 더는 쓸 수 없다고 알려준 토큰을 지운다.
    @Transactional
    public void removeTokens(Collection<String> tokens) {
        if (tokens.isEmpty()) {
            return;
        }
        deviceTokenRepository.deleteByTokenIn(tokens);
    }
}
