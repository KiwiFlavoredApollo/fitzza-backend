-- user-service 로컬·QA 테스트용 사용자 시드 (GitHub 이슈 #93)
--
-- 넣는 것: 테스트 사용자 20명(user_id 1001~1020)과 각자의 신체 프로필(user_body)
--   - 1001~1019: 프로필(성별·키·몸무게·신발 사이즈·선호 핏·선호 스타일)을 모두 채운 사용자
--   - 1020: 가입만 하고 온보딩을 건너뛴 사용자(프로필 값이 모두 비어 있음)
-- 로그인: 이메일 seed01@fitzza.test ~ seed20@fitzza.test, 비밀번호는 모두 Fitzza1234
--   (실제 사용자가 아닌 테스트 계정용 값이다. 비밀번호가 공개돼 있으니 운영 DB에는 넣지 않는다.)
--
-- 실행 순서: user-service를 한 번 띄워 테이블(user_service.users, user_body)이 만들어진 뒤에 실행한다.
--   docker compose exec -T postgres psql -U fitzza -d fitzza < infra/seed/user_service_seed.sql
-- 여러 번 실행해도 된다. 이미 있는 user_id·이메일·닉네임은 건너뛰고, 기존 데이터는 바꾸지 않는다.
-- 값 규칙은 회원가입·프로필 API와 같다(성별 M/F, 핏·스타일은 영문 코드, 스타일은 쉼표로 이어 붙임).

BEGIN;

INSERT INTO user_service.users (user_id, email, password, nickname, status, created_at, updated_at)
VALUES
    (1001, 'seed01@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '테스트유저', 'ACTIVE', now(), now()),
    (1002, 'seed02@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '코디요정', 'ACTIVE', now(), now()),
    (1003, 'seed03@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '스트릿킹', 'ACTIVE', now(), now()),
    (1004, 'seed04@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '미니멀리스트', 'ACTIVE', now(), now()),
    (1005, 'seed05@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '빈티지러버', 'ACTIVE', now(), now()),
    (1006, 'seed06@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '오늘뭐입지', 'ACTIVE', now(), now()),
    (1007, 'seed07@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '출근룩장인', 'ACTIVE', now(), now()),
    (1008, 'seed08@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '운동하는곰', 'ACTIVE', now(), now()),
    (1009, 'seed09@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '데일리룩', 'ACTIVE', now(), now()),
    (1010, 'seed10@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '셔츠덕후', 'ACTIVE', now(), now()),
    (1011, 'seed11@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '꾸안꾸', 'ACTIVE', now(), now()),
    (1012, 'seed12@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '러닝크루', 'ACTIVE', now(), now()),
    (1013, 'seed13@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '레트로감성', 'ACTIVE', now(), now()),
    (1014, 'seed14@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '키큰남자', 'ACTIVE', now(), now()),
    (1015, 'seed15@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '작은키코디', 'ACTIVE', now(), now()),
    (1016, 'seed16@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '주말나들이', 'ACTIVE', now(), now()),
    (1017, 'seed17@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '모노톤', 'ACTIVE', now(), now()),
    (1018, 'seed18@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '하객룩고민', 'ACTIVE', now(), now()),
    (1019, 'seed19@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '편한게최고', 'ACTIVE', now(), now()),
    (1020, 'seed20@fitzza.test', '$2a$10$Htmg7g8hQsJ5AiNVd1NceuwMdB9mnx7haGYiTiTbiwe5fQkrPY5YC', '온보딩전', 'ACTIVE', now(), now())
ON CONFLICT DO NOTHING;

-- 위에서 실제로 들어간(또는 이미 같은 이메일로 있던) 시드 사용자에게만 프로필을 만든다.
INSERT INTO user_service.user_body (user_id, height, weight, gender, preferred_styles, preferred_fit, shoe_size, updated_at)
SELECT b.user_id, b.height, b.weight, b.gender, b.preferred_styles, b.preferred_fit, b.shoe_size, now()
FROM (VALUES
    (1001::bigint, 175::real, 70::real, 'M'::varchar, 'CASUAL,MINIMAL'::varchar, 'REGULAR'::varchar, 270::integer),
    (1002, 163, 52, 'F', 'MINIMAL', 'SLIM', 240),
    (1003, 180, 78, 'M', 'STREET', 'OVERSIZED', 280),
    (1004, 168, 55, 'F', 'MINIMAL,FORMAL', 'REGULAR', 245),
    (1005, 172, 65, 'M', 'VINTAGE,CASUAL', 'RELAXED', 265),
    (1006, 158, 48, 'F', 'CASUAL', 'CROPPED', 230),
    (1007, 178, 74, 'M', 'FORMAL', 'SLIM', 275),
    (1008, 185, 90, 'M', 'SPORTY', 'RELAXED', 290),
    (1009, 165, 58, 'F', 'CASUAL,STREET', 'RELAXED', 240),
    (1010, 170, 63, 'M', 'MINIMAL,FORMAL', 'REGULAR', 260),
    (1011, 160, 50, 'F', 'CASUAL,MINIMAL', 'OVERSIZED', 235),
    (1012, 167, 54, 'F', 'SPORTY,CASUAL', 'SLIM', 245),
    (1013, 162, 53, 'F', 'VINTAGE', 'REGULAR', 240),
    (1014, 190, 85, 'M', 'STREET,SPORTY', 'REGULAR', 295),
    (1015, 165, 60, 'M', 'CASUAL', 'SLIM', 255),
    (1016, 170, 60, 'F', 'CASUAL,VINTAGE', 'RELAXED', 250),
    (1017, 176, 68, 'M', 'MINIMAL,STREET', 'OVERSIZED', 270),
    (1018, 164, 51, 'F', 'FORMAL,MINIMAL', 'SLIM', 235),
    (1019, 173, 77, 'M', 'SPORTY,CASUAL', 'RELAXED', 265),
    (1020, NULL, NULL, NULL, NULL, NULL, NULL)
) AS b(user_id, height, weight, gender, preferred_styles, preferred_fit, shoe_size)
JOIN user_service.users u
  ON u.user_id = b.user_id AND u.email = 'seed' || lpad((b.user_id - 1000)::text, 2, '0') || '@fitzza.test'
ON CONFLICT (user_id) DO NOTHING;

-- user_id를 직접 넣었으므로, 이후 회원가입이 시드 ID와 겹치지 않게 ID 시퀀스를 가장 큰 값 뒤로 옮긴다.
SELECT setval(pg_get_serial_sequence('user_service.users', 'user_id'),
              (SELECT max(user_id) FROM user_service.users));

COMMIT;

-- 확인: 20이 아니면 같은 이메일·닉네임이 이미 있어 건너뛴 시드 사용자가 있다.
SELECT count(*) AS seed_users FROM user_service.users WHERE email LIKE 'seed__@fitzza.test';
