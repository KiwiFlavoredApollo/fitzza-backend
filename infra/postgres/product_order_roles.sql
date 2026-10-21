-- product-service / order-service 전용 DB 계정 (로컬·RDS 공통 적용용).
-- 앱 슈퍼유저(또는 관리자)로 실행한다. 기존 볼륨·RDS에는 init.sql이 다시 안 돌므로 이 파일을 따로 적용한다.
--   docker compose exec -T postgres psql -U fitzza -d fitzza < infra/postgres/product_order_roles.sql
-- 로컬 기본 비밀번호는 fitzza_local이다. RDS에서는 배포 Secret 값으로 비밀번호를 바꾼 뒤 적용한다.

CREATE SCHEMA IF NOT EXISTS product_service;
CREATE SCHEMA IF NOT EXISTS order_service;

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

-- Hibernate 등으로 이미 만들어진 객체가 있으면 소유권·권한을 맞춘다.
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA product_service TO product_service;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA product_service TO product_service;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA order_service TO order_service;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA order_service TO order_service;

ALTER DEFAULT PRIVILEGES FOR ROLE product_service IN SCHEMA product_service
  GRANT ALL ON TABLES TO product_service;
ALTER DEFAULT PRIVILEGES FOR ROLE product_service IN SCHEMA product_service
  GRANT ALL ON SEQUENCES TO product_service;
ALTER DEFAULT PRIVILEGES FOR ROLE order_service IN SCHEMA order_service
  GRANT ALL ON TABLES TO order_service;
ALTER DEFAULT PRIVILEGES FOR ROLE order_service IN SCHEMA order_service
  GRANT ALL ON SEQUENCES TO order_service;
