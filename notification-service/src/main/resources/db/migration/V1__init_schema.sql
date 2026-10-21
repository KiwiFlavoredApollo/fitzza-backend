-- notification-service 첫 스키마. Hibernate ddl-auto: update가 만들던 테이블을 그대로 옮겼다.
-- 스키마 이름은 쓰지 않는다. Flyway가 spring.flyway.default-schema(notification_service)를 기준으로 실행한다.

create table notification_logs (
    log_id varchar(36) not null,
    user_id bigint not null,
    type varchar(20) not null,
    content text not null,
    target_id varchar(100),
    event_id varchar(100) not null,
    is_read boolean not null,
    created_at timestamp(6) with time zone not null,
    constraint notification_logs_pkey primary key (log_id),
    constraint uk_notification_logs_event unique (event_id),
    constraint notification_logs_type_check
        check (type in ('COMMENT', 'VOTE_CLOSED', 'FITTING_DONE', 'FITTING_FAILED', 'RECOMMENDATION_DONE'))
);

create index idx_notification_logs_user_created on notification_logs (user_id, created_at);

create table device_tokens (
    token varchar(512) not null,
    user_id bigint not null,
    updated_at timestamp(6) with time zone not null,
    constraint device_tokens_pkey primary key (token)
);

create index idx_device_tokens_user on device_tokens (user_id);
