-- product-service wishlist 시드 (소량)
-- 전제:
--   1) infra/seed/user_service_seed.sql (user_id=1001, seed01@fitzza.test / Fitzza1234)
--   2) infra/seed/product_service_seed.sql (product_id=4695999)
-- 실행:
--   docker compose exec -T postgres psql -U fitzza -d fitzza < infra/seed/product_service_wishlist_seed.sql

BEGIN;

INSERT INTO product_service.wishlist (user_id, item_id, item_type, created_at)
VALUES (1001, '4695999', 'PRODUCT', NOW())
ON CONFLICT (user_id, item_id, item_type) DO NOTHING;

COMMIT;

-- 확인: SELECT * FROM product_service.wishlist WHERE user_id = 1001;
