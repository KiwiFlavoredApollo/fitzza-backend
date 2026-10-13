package com.fitzza.product.global.constant;

public final class ApiHeaders {

    public static final String USER_ID = "X-User-Id";

    // 서비스끼리 호출할 때만 쓰는 헤더. 값은 호출하는 서비스마다 다른 토큰이다.
    public static final String SERVICE_TOKEN = "X-Service-Token";

    private ApiHeaders() {
    }
}
