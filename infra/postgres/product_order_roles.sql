-- product·order 전용 DB 계정(<스키마>_app)을 만들고, 그 계정을 자기 스키마의 소유자로 만든다.
-- 지현님 service-accounts.sql과 동일 규약(계정명·getenv·소유권 이전). product/order만 적용.
--
-- 관리자 계정(로컬 POSTGRES_USER, RDS 마스터)으로 실행. psql 15+ 필요.
-- 비밀번호는 환경 변수로만 받는다. 없으면 해당 서비스는 건너뛴다.
--   PRODUCT_SERVICE_DB_PASSWORD, ORDER_SERVICE_DB_PASSWORD
-- 예:
--   export PRODUCT_SERVICE_DB_PASSWORD=fitzza_local ORDER_SERVICE_DB_PASSWORD=fitzza_local
--   psql -v ON_ERROR_STOP=1 -U fitzza -d fitzza -f infra/postgres/product_order_roles.sql
-- 다시 실행해도 된다.

\set ON_ERROR_STOP on

\set svc product_service
\unset pw
\getenv pw PRODUCT_SERVICE_DB_PASSWORD
\ir service-account.psql

\set svc order_service
\unset pw
\getenv pw ORDER_SERVICE_DB_PASSWORD
\ir service-account.psql

\unset pw
