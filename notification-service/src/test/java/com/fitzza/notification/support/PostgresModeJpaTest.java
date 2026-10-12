package com.fitzza.notification.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

// 저장소가 쓰는 PostgreSQL 문법(on conflict do nothing 등)을 실행할 수 있도록 H2를 PostgreSQL 호환 모드로 띄운다.
// 테스트마다 트랜잭션을 되돌리므로 테스트끼리 데이터가 섞이지 않는다.
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@DataJpaTest(properties = {
    "spring.cloud.config.enabled=false",
    "spring.test.database.replace=none",
    "spring.datasource.url=jdbc:h2:mem:notification_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
public @interface PostgresModeJpaTest {
}
