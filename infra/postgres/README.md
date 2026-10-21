# PostgreSQL 초기화 (로컬)

`init.sql`은 Compose Postgres **최초 기동** 시 스키마와 product/order 전용 계정을 만든다.

| 역할 | 스키마 | 로컬 기본 비밀번호 |
|------|--------|-------------------|
| `product_service` | `product_service` | `fitzza_local` |
| `order_service` | `order_service` | `fitzza_local` |

공용 계정 `fitzza`는 그대로 두고, product·order 앱만 전용 계정으로 접속한다.

## 기존 볼륨 / RDS

`init.sql`은 이미 있는 데이터 디렉터리에는 다시 실행되지 않는다. 아래를 한 번 적용한다.

```bash
set -a && source .env && set +a
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  < infra/postgres/product_order_roles.sql
```

RDS에서는 관리자 계정으로 같은 SQL을 실행하고, 비밀번호는 Secret 값에 맞게 바꾼다.
