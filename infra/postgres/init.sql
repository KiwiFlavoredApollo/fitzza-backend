CREATE SCHEMA IF NOT EXISTS user_service;
CREATE SCHEMA IF NOT EXISTS product_service;
CREATE SCHEMA IF NOT EXISTS order_service;
CREATE SCHEMA IF NOT EXISTS community_service;
CREATE SCHEMA IF NOT EXISTS review_service;
CREATE SCHEMA IF NOT EXISTS notification_service;

COMMENT ON SCHEMA user_service IS 'Owned by user-service';
COMMENT ON SCHEMA product_service IS 'Owned by product-service';
COMMENT ON SCHEMA order_service IS 'Owned by order-service';
COMMENT ON SCHEMA community_service IS 'Owned by community-service';
COMMENT ON SCHEMA review_service IS 'Owned by review-service';
COMMENT ON SCHEMA notification_service IS 'Owned by notification-service';

-- product / order 전용 로그인 역할. 신규 볼륨에서만 자동 적용된다.
-- 기존 DB·RDS는 infra/postgres/product_order_roles.sql을 따로 실행한다.
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'product_service') THEN
    CREATE ROLE product_service LOGIN PASSWORD 'fitzza_local';
  END IF;
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'order_service') THEN
    CREATE ROLE order_service LOGIN PASSWORD 'fitzza_local';
  END IF;
END
$$;

GRANT CONNECT ON DATABASE fitzza TO product_service;
GRANT CONNECT ON DATABASE fitzza TO order_service;

GRANT USAGE, CREATE ON SCHEMA product_service TO product_service;
GRANT USAGE, CREATE ON SCHEMA order_service TO order_service;

ALTER SCHEMA product_service OWNER TO product_service;
ALTER SCHEMA order_service OWNER TO order_service;
