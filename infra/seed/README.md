# Local / QA SQL seeds

| 파일 | 스키마 | 설명 |
|------|--------|------|
| `user_service_seed.sql` | `user_service` | 테스트 사용자 1001~1020 |
| `product_service_seed.sql` | `product_service` | 무신사 `4695999` 상품 1건 (`product_id` = 무신사 번호) |
| `product_service_wishlist_seed.sql` | `product_service` | user `1001` + product `4695999` 찜 1건 |

## Apply order

```bash
set -a && source .env && set +a
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" < infra/seed/user_service_seed.sql
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" < infra/seed/product_service_seed.sql
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" < infra/seed/product_service_wishlist_seed.sql
```

- 상품: `GET /api/v1/products/4695999`
- 찜: `seed01@fitzza.test` / `Fitzza1234` signin 후 `GET /api/v1/wishlists`

`product_service_seed.sql`은 `product_id`=무신사 번호, `option_id`=DB IDENTITY(`source_option_id`에 무신사 옵션 번호) 기준이다. Java musinsa JSON 시드와 별개 픽스처.

재실행 시 이미지·실측·wishlist·worldcup은 `product_id=4695999` 범위로 갱신하고,
기존 `source_option_id`의 `option_id`는 보존한다(장바구니·주문에 묶인 옵션 ID 유지).
wishlist·worldcup_history는 해당 상품 기준 사용자 구분 없이 삭제되므로 QA에서는 이 상품 관련 기록이 초기화된다.
