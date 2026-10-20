-- order-service 로컬·QA 테스트용 완료 주문 시드
--
-- 넣는 것: 시드 사용자 1001~1005의 주문 6건(order_id 1001~1006)과 주문 항목 7개(order_item_id 1001~1007)
--   상품은 product_service_seed.sql의 4695999(릴렉스드 쓰리 핀 스트라이프 포플린 셔츠) 옵션을 쓴다.
--   | 사용자 | 주문 | 상태      | 주문 항목 (옵션)                      | 쓰임                          |
--   | 1001   | 1001 | COMPLETED | 1001 (M 21092423), 1002 (L 21092424)  | 리뷰 1건 + 설문 1건 따로 써 보기 |
--   | 1002   | 1002 | COMPLETED | 1003 (M 21092423)                     | 리뷰·설문                      |
--   | 1003   | 1003 | COMPLETED | 1004 (L 21092424)                     | 리뷰·설문                      |
--   | 1004   | 1004 | COMPLETED | 1005 (XL 21092425)                    | 리뷰·설문                      |
--   | 1005   | 1005 | COMPLETED | 1006 (S 21092422)                     | 리뷰·설문                      |
--   | 1005   | 1006 | CANCELED  | 1007 (S 21092422)                     | 완료되지 않은 주문이라 거부되는지 확인 |
-- 리뷰·설문은 주문 항목 하나에 한 번씩만 쓸 수 있다.
--
-- 실행 순서: user_service_seed.sql → product_service_seed.sql → 이 파일 (order-service를 한 번 띄워 테이블이 생긴 뒤)
--   docker compose exec -T postgres psql -U fitzza -d fitzza < infra/seed/order_service_seed.sql
-- 여러 번 실행해도 된다. 이미 있는 주문 ID·주문 번호는 건너뛰고, 기존 데이터는 바꾸지 않는다.

BEGIN;

INSERT INTO order_service.orders (
  order_id, order_num, user_id, total_price, order_status,
  recipient_name, recipient_phone, shipping_address, created_at, updated_at
) OVERRIDING SYSTEM VALUE VALUES
  (1001, 'SEED-ORDER-1001', 1001, 55780, 'COMPLETED', '테스트유저', '010-0000-1001', '서울특별시 중구 세종대로 110', now(), now()),
  (1002, 'SEED-ORDER-1002', 1002, 27890, 'COMPLETED', '코디요정', '010-0000-1002', '서울특별시 중구 세종대로 110', now(), now()),
  (1003, 'SEED-ORDER-1003', 1003, 27890, 'COMPLETED', '스트릿킹', '010-0000-1003', '서울특별시 중구 세종대로 110', now(), now()),
  (1004, 'SEED-ORDER-1004', 1004, 27890, 'COMPLETED', '미니멀리스트', '010-0000-1004', '서울특별시 중구 세종대로 110', now(), now()),
  (1005, 'SEED-ORDER-1005', 1005, 27890, 'COMPLETED', '빈티지러버', '010-0000-1005', '서울특별시 중구 세종대로 110', now(), now()),
  (1006, 'SEED-ORDER-1006', 1005, 27890, 'CANCELED', '빈티지러버', '010-0000-1005', '서울특별시 중구 세종대로 110', now(), now())
ON CONFLICT DO NOTHING;

-- 위에서 실제로 들어간(또는 이미 같은 주문 번호로 있던) 시드 주문에만 항목을 붙인다.
INSERT INTO order_service.order_item (order_item_id, order_id, option_id, order_qty, unit_price)
OVERRIDING SYSTEM VALUE
SELECT i.order_item_id, i.order_id, i.option_id, i.order_qty, i.unit_price
FROM (VALUES
  (1001::bigint, 1001::bigint, 21092423::bigint, 1, 27890),
  (1002, 1001, 21092424, 1, 27890),
  (1003, 1002, 21092423, 1, 27890),
  (1004, 1003, 21092424, 1, 27890),
  (1005, 1004, 21092425, 1, 27890),
  (1006, 1005, 21092422, 1, 27890),
  (1007, 1006, 21092422, 1, 27890)
) AS i(order_item_id, order_id, option_id, order_qty, unit_price)
JOIN order_service.orders o
  ON o.order_id = i.order_id AND o.order_num = 'SEED-ORDER-' || i.order_id
ON CONFLICT DO NOTHING;

-- ID를 직접 넣었으므로, 이후 실제 주문이 시드 ID와 겹치지 않게 시퀀스를 가장 큰 값 뒤로 옮긴다.
SELECT setval(pg_get_serial_sequence('order_service.orders', 'order_id'),
              (SELECT max(order_id) FROM order_service.orders));
SELECT setval(pg_get_serial_sequence('order_service.order_item', 'order_item_id'),
              (SELECT max(order_item_id) FROM order_service.order_item));

COMMIT;

-- 확인: 6 / 7이 아니면 같은 ID·주문 번호가 이미 있어 건너뛴 시드가 있다.
SELECT (SELECT count(*) FROM order_service.orders WHERE order_num LIKE 'SEED-ORDER-%') AS seed_orders,
       (SELECT count(*) FROM order_service.order_item i
          JOIN order_service.orders o ON o.order_id = i.order_id
         WHERE o.order_num LIKE 'SEED-ORDER-%') AS seed_order_items;
