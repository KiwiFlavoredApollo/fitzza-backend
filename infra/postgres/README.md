# PostgreSQL 초기화 (로컬)

`init.sql`은 Compose Postgres **최초 기동** 시 스키마만 만든다.
서비스 전용 DB 계정은 `<스키마>_app`(예: `product_service_app`)이며, 스크립트로 별도 생성한다.

| 역할 | 스키마 | env (비밀번호) |
|------|--------|----------------|
| `product_service_app` | `product_service` | `PRODUCT_SERVICE_DB_PASSWORD` |
| `order_service_app` | `order_service` | `ORDER_SERVICE_DB_PASSWORD` |

앱 설정은 `PRODUCT_SERVICE_DB_USER`/`_PASSWORD`가 있으면 전용 계정을 쓰고, 없으면 공용 `POSTGRES_USER`/`POSTGRES_PASSWORD`로 fallback한다.

## product / order 계정 적용

`\ir`가 같은 디렉터리의 `service-account.psql`을 참조하므로 `infra/postgres`에서 실행한다. psql 15+ 필요.

```bash
cd infra/postgres
export PRODUCT_SERVICE_DB_PASSWORD="${PRODUCT_SERVICE_DB_PASSWORD:-fitzza_local}"
export ORDER_SERVICE_DB_PASSWORD="${ORDER_SERVICE_DB_PASSWORD:-fitzza_local}"
psql -v ON_ERROR_STOP=1 -h localhost -U "${POSTGRES_USER:-fitzza}" -d "${POSTGRES_DB:-fitzza}" \
  -f product_order_roles.sql
```

비밀번호 env가 없는 서비스는 건너뛴다. 다시 실행해도 된다(비밀번호 갱신·기존 테이블 소유권 이전).
