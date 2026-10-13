package com.fitzza.notification.security;

public final class AuthHeaders {

    // 게이트웨이가 토큰을 검증한 뒤 붙여 주는 헤더. 서비스는 이 값으로만 사용자를 식별한다.
    public static final String USER_ID = "X-User-Id";

    // 서비스끼리 호출할 때만 쓰는 헤더. 값은 호출하는 서비스마다 다른 토큰이다.
    public static final String SERVICE_TOKEN = "X-Service-Token";

    private AuthHeaders() {
    }
}
