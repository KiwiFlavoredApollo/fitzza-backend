-- 서비스별 DB 계정(<스키마>_app)을 만들고, 그 계정을 자기 스키마의 소유자로 만든다.
-- 소유자는 자기 스키마 안에서 테이블을 만들고 바꿀 수 있어 Flyway가 그대로 동작하고,
-- 다른 서비스 스키마에는 권한이 없어 접근할 수 없다.
--
-- 관리자 계정(로컬 POSTGRES_USER, RDS 마스터 계정)으로 서비스 DB에 접속해 실행한다. psql 15 이상이 필요하다.
-- 비밀번호는 명령줄·파일에 남지 않도록 환경 변수로만 받는다. 비밀번호 환경 변수가 없는 서비스는 건너뛴다.
--   USER_SERVICE_DB_PASSWORD, PRODUCT_SERVICE_DB_PASSWORD, ORDER_SERVICE_DB_PASSWORD,
--   COMMUNITY_SERVICE_DB_PASSWORD, REVIEW_SERVICE_DB_PASSWORD, NOTIFICATION_SERVICE_DB_PASSWORD
-- 예: psql -v ON_ERROR_STOP=1 -U <관리자> -d fitzza -f infra/postgres/service-accounts.sql
-- 다시 실행해도 된다. 계정이 있으면 비밀번호를 바꾸고, 관리자 계정이 만든 기존 테이블의 소유자도 넘긴다.

\set ON_ERROR_STOP on

\set svc user_service
\unset pw
\getenv pw USER_SERVICE_DB_PASSWORD
\ir service-account.psql

\set svc product_service
\unset pw
\getenv pw PRODUCT_SERVICE_DB_PASSWORD
\ir service-account.psql

\set svc order_service
\unset pw
\getenv pw ORDER_SERVICE_DB_PASSWORD
\ir service-account.psql

\set svc community_service
\unset pw
\getenv pw COMMUNITY_SERVICE_DB_PASSWORD
\ir service-account.psql

\set svc review_service
\unset pw
\getenv pw REVIEW_SERVICE_DB_PASSWORD
\ir service-account.psql

\set svc notification_service
\unset pw
\getenv pw NOTIFICATION_SERVICE_DB_PASSWORD
\ir service-account.psql

\unset pw
