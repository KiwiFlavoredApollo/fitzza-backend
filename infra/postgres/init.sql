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

-- 서비스 전용 로그인 역할(<스키마>_app)은 init에 넣지 않는다.
-- product/order: infra/postgres/product_order_roles.sql
-- (전 서비스 통합본은 지현님 service-accounts.sql과 동일 규약)
