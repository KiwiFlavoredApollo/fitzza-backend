# Local / QA SQL seeds

| 파일 | 스키마 | 설명 |
|------|--------|------|
| `user_service_seed.sql` | `user_service` | 테스트 사용자 1001~1020 |
| `product_service_seed.sql` | `product_service` | 무신사 `4695999` 상품 1건 (`product_id` = 무신사 번호) |
| `product_service_wishlist_seed.sql` | `product_service` | user `1001` + product `4695999` 찜 1건 |
| `order_service_seed.sql` | `order_service` | user `1001`~`1005` 주문 6건(완료 5, 취소 1)·주문 항목 7개 — 리뷰·사이즈 설문 테스트용 |

## Apply order

```bash
set -a && source .env && set +a
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" < infra/seed/user_service_seed.sql
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" < infra/seed/product_service_seed.sql
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" < infra/seed/product_service_wishlist_seed.sql
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" < infra/seed/order_service_seed.sql
```

- 상품: `GET /api/v1/products/4695999`
- 찜: `seed01@fitzza.test` / `Fitzza1234` signin 후 `GET /api/v1/wishlists`
- 주문·리뷰·설문: `seed01@fitzza.test`로 signin 후 주문 항목 `1001`, `1002`로 `POST /api/v1/reviews`·`POST /api/v1/surveys`
- 취소 주문 거부: `seed05@fitzza.test`로 signin 후 주문 항목 `1007`로 요청하면 `ORDER_NOT_COMPLETED`(다른 계정으로 요청하면 소유권 오류 `NOT_ORDER_OWNER`가 먼저 난다)
- `-v ON_ERROR_STOP=1`: SQL 오류가 나면 거기서 멈추고 실패로 끝나게 한다(없으면 오류가 나도 명령이 성공한 것처럼 끝날 수 있다)

`product_service_seed.sql`은 `product_id`=무신사 번호, `option_id`=DB IDENTITY(`source_option_id`에 무신사 옵션 번호) 기준이다. Java musinsa JSON 시드와 별개 픽스처.

재실행 시 이미지·실측·wishlist·worldcup은 `product_id=4695999` 범위로 갱신하고,
기존 `source_option_id`의 `option_id`는 보존한다(장바구니·주문에 묶인 옵션 ID 유지).
wishlist·worldcup_history는 해당 상품 기준 사용자 구분 없이 삭제되므로 QA에서는 이 상품 관련 기록이 초기화된다.
