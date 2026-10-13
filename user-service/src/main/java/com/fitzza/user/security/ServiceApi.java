package com.fitzza.user.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 다른 서비스만 호출하는 API에 붙인다. callers에 적힌 서비스의 토큰(X-Service-Token)으로만 호출할 수 있다.
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ServiceApi {

    String[] callers();
}
