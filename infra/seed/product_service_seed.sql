-- product-service 로컬·QA 테스트용 상품 시드
-- 원본: final_products 상의/4695999.json (무신사 product_id = DB/API product_id)
-- 전제: product_id=무신사 번호, option_id=DB IDENTITY(무신사 옵션 번호는 source_option_id).
-- 실행 순서: product-service 기동으로 테이블 생성 후
--   docker compose exec -T postgres psql -U fitzza -d fitzza < infra/seed/product_service_seed.sql
-- 재실행 가능: product_id=4695999 관련 행만 먼저 지운다.
-- 주의: 아래 wishlist/worldcup_history DELETE는 해당 상품에 대한 모든 사용자 데이터를 지운다.

BEGIN;

-- cleanup product_id=4695999 (다른 상품 옵션은 건드리지 않음)
DELETE FROM product_service.option_measurement
WHERE option_id IN (
  SELECT option_id FROM product_service.product_option
  WHERE product_id = 4695999
);
DELETE FROM product_service.product_option WHERE product_id = 4695999;
DELETE FROM product_service.product_image WHERE product_id = 4695999;
DELETE FROM product_service.product_size_guide WHERE product_id = 4695999;
DELETE FROM product_service.wishlist WHERE item_type = 'PRODUCT' AND item_id = '4695999';
DELETE FROM product_service.worldcup_history WHERE winner_product_id = 4695999;
DELETE FROM product_service.product WHERE product_id = 4695999;

INSERT INTO product_service.category (category_code, category_name, parent_code, depth) VALUES
  ('001', '상의', NULL, 1)
ON CONFLICT (category_code) DO UPDATE SET category_name = EXCLUDED.category_name;
INSERT INTO product_service.category (category_code, category_name, parent_code, depth) VALUES
  ('001002', '셔츠/블라우스', '001', 2)
ON CONFLICT (category_code) DO UPDATE SET category_name = EXCLUDED.category_name, parent_code = EXCLUDED.parent_code;

INSERT INTO product_service.brand (brand_name, brand_name_en, brand_code)
SELECT '무신사 스탠다드', 'MUSINSA STANDARD', 'musinsastandard'
WHERE NOT EXISTS (
  SELECT 1 FROM product_service.brand b WHERE b.brand_code = 'musinsastandard' OR b.brand_name = '무신사 스탠다드'
);

INSERT INTO product_service.product (
  product_id, brand_id, source, source_url,
  product_name, product_name_en, style_no, gender,
  category_1, category_2, subcategory,
  price, normal_price, discount_rate, season, features, image_url, status,
  created_at, updated_at
) VALUES (
  4695999,
  (SELECT brand_id FROM product_service.brand WHERE brand_code = 'musinsastandard' OR brand_name = '무신사 스탠다드' ORDER BY brand_id LIMIT 1),
  'musinsa', 'https://www.musinsa.com/products/4695999',
  '릴렉스드 쓰리 핀 스트라이프 포플린 셔츠 [블루/화이트]', 'RELAXED THREE PIN STRIPE POPLIN SHIRT [BLUE/WHITE]', 'MMESL4Z21-N3', 'MALE',
  '001', '001002', NULL,
  27890, 39900, 30, 'SUMMER',
  '{"핏": ["루즈"], "촉감": ["보통"], "신축성": ["보통"], "비침": ["보통"], "두께": ["보통"], "계절": ["봄", "여름", "가을"]}'::jsonb, 'https://image.msscdn.net/images/goods_img/20250103/4695999/4695999_17510204984958_500.jpg', 'ON_SALE',
  NOW(), NOW()
);

INSERT INTO product_service.product_size_guide (product_id, type_name, unit, description, guide_image_url) VALUES (
  4695999, '셔츠', 'cm', '측정 방법에 따라 약 2cm 정도의 오차가 있을 수 있습니다.', 'https://image.musinsa.com/images/size_type/detail_img/2026090711285100000081122.png'
);

INSERT INTO product_service.product_image (product_id, image_url, sequence) VALUES (4695999, 'https://image.msscdn.net/images/goods_img/20250103/4695999/4695999_17510204984958_500.jpg', 0);
INSERT INTO product_service.product_image (product_id, image_url, sequence) VALUES (4695999, 'https://image.msscdn.net/images/prd_img/20250103/4695999/detail_4695999_17510205053103_500.jpg', 1);
INSERT INTO product_service.product_image (product_id, image_url, sequence) VALUES (4695999, 'https://image.msscdn.net/images/prd_img/20250103/4695999/detail_4695999_17510205061505_500.jpg', 2);
INSERT INTO product_service.product_image (product_id, image_url, sequence) VALUES (4695999, 'https://image.msscdn.net/images/prd_img/20250103/4695999/detail_4695999_17453041738997_500.jpg', 3);
INSERT INTO product_service.product_image (product_id, image_url, sequence) VALUES (4695999, 'https://image.msscdn.net/images/prd_img/20250103/4695999/detail_4695999_17453041749751_500.jpg', 4);
INSERT INTO product_service.product_image (product_id, image_url, sequence) VALUES (4695999, 'https://image.msscdn.net/images/prd_img/20250103/4695999/detail_4695999_17453041759966_500.jpg', 5);
INSERT INTO product_service.product_image (product_id, image_url, sequence) VALUES (4695999, 'https://image.msscdn.net/images/prd_img/20250103/4695999/detail_4695999_17453041770919_500.jpg', 6);

-- option_id는 IDENTITY 자동발급. 무신사 옵션 번호는 source_option_id에만 넣는다.
INSERT INTO product_service.product_option (
  product_id, source_option_id, managed_code, color, size, additional_price, image_url
) VALUES
  (4695999, 21092422, 'S', NULL, 'S', 0, NULL),
  (4695999, 21092423, 'M', NULL, 'M', 0, NULL),
  (4695999, 21092424, 'L', NULL, 'L', 0, NULL),
  (4695999, 21092425, 'XL', NULL, 'XL', 0, NULL),
  (4695999, 21092426, '2XL', NULL, '2XL', 0, NULL);

INSERT INTO product_service.option_measurement (option_id, size_label, sequence, measurement_spec)
SELECT o.option_id, v.size_label, v.sequence, v.measurement_spec::jsonb
FROM (VALUES
  (21092422, 'S', 0, '{"총장": 75.0, "어깨너비": 49.8, "가슴단면": 58.0, "소매길이": 59.5, "밑단단면": 56.0, "소매부리단면": 11.0, "암홀": 21.2}'),
  (21092423, 'M', 1, '{"총장": 76.5, "어깨너비": 51.4, "가슴단면": 60.5, "소매길이": 60.5, "밑단단면": 58.5, "소매부리단면": 11.5, "암홀": 22.0}'),
  (21092424, 'L', 2, '{"총장": 78.0, "어깨너비": 53.0, "가슴단면": 63.0, "소매길이": 61.5, "밑단단면": 61.0, "소매부리단면": 12.0, "암홀": 22.8}'),
  (21092425, 'XL', 3, '{"총장": 79.5, "어깨너비": 54.6, "가슴단면": 65.5, "소매길이": 62.5, "밑단단면": 63.5, "소매부리단면": 12.5, "암홀": 23.6}'),
  (21092426, '2XL', 4, '{"총장": 80.5, "어깨너비": 56.2, "가슴단면": 68.0, "소매길이": 63.0, "밑단단면": 66.0, "소매부리단면": 13.0, "암홀": 24.4}')
) AS v(source_option_id, size_label, sequence, measurement_spec)
JOIN product_service.product_option o
  ON o.product_id = 4695999 AND o.source_option_id = v.source_option_id;

COMMIT;

-- 확인:
-- SELECT product_id, product_name, price FROM product_service.product WHERE product_id = 4695999;
-- SELECT option_id, source_option_id, size FROM product_service.product_option WHERE product_id = 4695999;
