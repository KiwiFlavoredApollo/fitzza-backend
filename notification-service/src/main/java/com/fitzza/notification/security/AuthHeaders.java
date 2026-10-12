package com.fitzza.notification.security;

public final class AuthHeaders {

    // 게이트웨이가 토큰을 검증한 뒤 붙여 주는 헤더. 서비스는 이 값으로만 사용자를 식별한다.
    public static final String USER_ID = "X-User-Id";

    // 서비스끼리 호출할 때만 쓰는 헤더. 값은 INTERNAL_CALL_TOKEN과 같아야 한다.
    public static final String INTERNAL_TOKEN = "X-Internal-Token";

    private AuthHeaders() {
    }
}
