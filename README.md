# Fitzza MSA Backend

Fitzza 패션 커머스의 최소 실행형 MSA 골격입니다. 각 서비스는 독립 배포 단위이며, 현재는 상태 확인용 API와 인프라 연결 설정까지만 제공합니다.

팀 브랜치·커밋·PR·코드 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md)를 따릅니다. AI 코딩 도구는 작업 전에 [AGENTS.md](AGENTS.md)를 먼저 확인해야 합니다.

## 구성

| 영역 | 서비스 | 포트 |
| --- | --- | ---: |
| Platform | Config Server | 8888 |
| Platform | Eureka Discovery | 8761 |
| Edge | API Gateway | 8080 |
| Commerce | User / Product / Order / Community / Review | 8081-8085 |
| Messaging | Notification | 8086 |
| AI | Virtual Try-On / Recommendation (FastAPI) | 8091-8092 |
| Infra | PostgreSQL / Redis / LocalStack(S3, SQS) | 5432 / 6379 / 4566 |

## 빠른 시작

요구 사항: Docker Desktop 및 Docker Compose

```bash
cp .env.example .env
# .env의 JWT_SECRET, COMMUNITY_SERVICE_TOKEN, ORDER_SERVICE_TOKEN에 서로 다른 32바이트 이상의 무작위 비밀값을 입력한 뒤 실행합니다.
docker compose up --build -d
```

Windows PowerShell에서는 `Copy-Item .env.example .env`를 사용합니다.

`JWT_SECRET`은 user-service가 Access Token을 서명하고 gateway-service가 검증하는 데 함께 쓰는 32바이트 이상 문자열입니다. 로컬과 배포 환경 모두 `.env` 또는 환경 변수로 직접 설정해야 합니다. 값이 없거나 비어 있으면 Docker Compose가 시작되지 않습니다. `.env.example`에는 비밀값을 제공하지 않습니다.

서비스끼리 호출하는 API는 `/api` 없이 시작하며(예: user-service `GET /users`, product-service `GET /products`, `GET /product-options`, notification-service `POST /notifications/events`) Gateway 라우트가 없습니다. Gateway에 `/api`로 시작하지 않는 라우트를 추가하지 않습니다. 호출하는 서비스는 자기 토큰을 `X-Service-Token` 헤더로 보내고, 받는 서비스는 API마다 허용한 서비스의 토큰인지 확인합니다. 현재 요청의 `X-User-Id`는 다른 서비스로 그대로 전달합니다. 토큰은 서비스마다 다른 32바이트 이상 값입니다.

| 토큰 | 보내는 서비스 | 호출할 수 있는 API |
| --- | --- | --- |
| `COMMUNITY_SERVICE_TOKEN` | community-service | user `GET /users`(닉네임), notification `POST /notifications/events`, product `GET /products`, `GET /wishlists/check`, `GET /worldcups/{id}` |
| `ORDER_SERVICE_TOKEN` | order-service | product `GET /product-options` |
| `RECOMMENDATION_SERVICE_TOKEN` | recommendation-service | user `GET /users/{userId}/body`(신체 정보), notification `POST /notifications/events`, product `GET /products/measurements` |
| `VTON_SERVICE_TOKEN` | 가상 피팅 서비스 | notification `POST /notifications/events` |
| `PRODUCT_SERVICE_TOKEN` | product-service | product-service가 다른 서비스를 호출할 때 보내는 토큰. 호출을 붙이기 전에는 비워 둔다. |
| `REVIEW_SERVICE_TOKEN` | review-service | order `GET /order-items/{orderItemId}`(리뷰·설문 작성 전 본인 주문 확인). order-service가 이 토큰을 허용하기 전에는 비워 두며, 그동안 리뷰·설문 작성은 503이다. |

`COMMUNITY_SERVICE_TOKEN`이 없거나 32바이트보다 짧으면 user·notification·community·product-service가 시작되지 않습니다. `ORDER_SERVICE_TOKEN`이 없거나 짧으면 order-service와 product-service가 시작되지 않습니다. 나머지 토큰은 해당 서비스가 호출을 시작할 때 설정합니다. 토큰이 없거나 허용되지 않은 서비스의 토큰이면 HTTP 401 `UNAUTHENTICATED`를 반환합니다. Gateway는 클라이언트가 보낸 `X-Service-Token`과 `X-Internal-Token`을 전달하기 전에 제거합니다.

user-service의 8081 포트와 notification-service의 8086 포트는 호스트에 공개하지 않습니다.

브라우저에서 Gateway를 호출할 FE 주소는 `CORS_ALLOWED_ORIGINS`(쉼표로 구분, 기본 `http://localhost:3000,http://localhost:5173`)로 정합니다. 목록에 없는 주소에서 온 브라우저 요청은 403으로 거부됩니다. 토큰은 `Authorization` 헤더로 보내므로 쿠키 허용은 켜지 않았습니다.

토큰 유효기간은 `JWT_EXPIRATION_SECONDS`(Access Token, 1~900초, 기본 15분)와 `JWT_REFRESH_EXPIRATION_SECONDS`(Refresh Token, 기본 30일)로 바꿀 수 있습니다. Refresh Token은 Redis에 저장합니다.

로그아웃(`POST /api/auth/signout`)은 Body의 Refresh Token을 Redis에서 지웁니다. `Authorization: Bearer <access-token>` 헤더를 함께 보내면 그 Access Token도 남은 만료 시간 동안 Redis 블랙리스트에 올라가고, Gateway는 블랙리스트에 있는 토큰을 401 `INVALID_TOKEN`으로 거부합니다. Gateway가 Redis를 조회하지 못하면 토큰이 실린 요청은 503 `AUTH_UNAVAILABLE`로 응답합니다.

알림 API(`GET /api/notifications`, `PATCH /api/notifications/{notificationId}`)는 로그인한 사용자의 알림만 다룹니다. 알림은 다른 서비스가 `POST /notifications/events`로 보낸 이벤트로 만들어지며, 같은 `eventId`로 다시 보내도 한 번만 저장됩니다. 다른 사람이 내 게시글에 댓글이나 답글을 달면 community-service가 댓글 저장이 확정된 뒤 별도 스레드에서 알림을 보냅니다. 알림 서비스가 꺼져 있으면 경고 로그만 남기고 댓글 작성은 그대로 성공합니다. 투표는 community-service가 1분마다(`fitzza.vote.close-check-delay-ms`, 기본 60000) 마감 시각이 지난 글을 찾아 마감 처리하고, 작성자에게 `VOTE_CLOSED` 알림을 보냅니다. 알림 서비스가 꺼져 있어 보내지 못한 알림은 다음 실행에서 성공할 때까지 다시 보냅니다. 실패한 글은 재시도 순서가 뒤로 밀려 새로 마감된 투표의 알림을 막지 않습니다. 작성자가 직접 조기 종료한 투표에는 알림을 보내지 않습니다.

product-service(8082), order-service(8083), community-service(8084), review-service(8085)는 호스트에 공개하지 않습니다. 이 서비스들은 `X-User-Id` 헤더를 로그인 사용자로 믿기 때문에, 포트가 열려 있으면 Gateway를 거치지 않고 헤더만 넣어 다른 사용자의 찜 여부를 보거나 장바구니를 바꾸거나 글을 쓰거나 지울 수 있습니다. 상품은 `http://localhost:8080/api/products`, 장바구니는 `http://localhost:8080/api/carts`, 커뮤니티는 `http://localhost:8080/api/community/...`처럼 Gateway로 호출합니다.

notification-service의 8086 포트는 호스트에 공개하지 않습니다. 서비스 간 호출은 Compose 네트워크의 `http://notification-service:8086`을 사용하고, 공개 알림 API는 `http://localhost:8080/api/notifications`로 Gateway를 통해 호출합니다. `X-User-Id`는 Gateway에서 검증한 사용자 ID만 신뢰합니다.

Gateway는 모든 전달 요청에서 클라이언트의 `X-User-Id`를 제거하고, 보호된 사용자 API에서는 서명·만료 시각·사용자 ID(subject)를 검증한 JWT의 ID로 설정합니다.

Gateway는 요청마다 `X-Request-Id`를 정해 뒤쪽 서비스로 넘기고 응답 헤더에도 담습니다(브라우저에서도 읽을 수 있음). 클라이언트가 영문·숫자·`._-` 8~64자로 보내면 그 값을 이어 쓰고, 아니면 새로 만듭니다. user·community·notification-service는 이 값을 로그 줄마다 `[요청 ID]`로 찍고, community-service는 다른 서비스를 호출할 때도 같은 값을 넘깁니다. 오류 문의를 받으면 응답의 `X-Request-Id`로 `docker compose logs | grep <ID>` 해서 같은 요청의 로그를 모아 볼 수 있습니다.

`POST /api/auth/signup`, `/api/auth/signin`, `/api/auth/refresh`, `/api/auth/signout`은 토큰 없이 호출할 수 있습니다. 회원가입에서 이메일이나 닉네임이 이미 쓰이고 있으면 409 `DUPLICATE_VALUE`와 함께 `errors` 배열에 중복된 필드를 모두 담아 돌려줍니다. 그 외 사용자 API(`GET /api/users/me`, `PATCH /api/users/me`)에는 `Authorization: Bearer <access-token>`이 필요합니다. 추천 API(`/api/v1/recommendations/**`)도 회원만 쓸 수 있어 토큰이 없으면 Gateway가 401 `UNAUTHENTICATED`로 막습니다. 인증 실패 시 Gateway는 401을 반환합니다.

```bash
curl -H "Authorization: Bearer <access-token>" http://localhost:8080/api/users/me
```

API 문서(Swagger UI)는 Gateway 한 곳에서 봅니다: <http://localhost:8080/swagger-ui.html>. 오른쪽 위 목록에서 user-service, community-service, notification-service를 고르고, 로그인이 필요한 API는 `Authorize`에 Access Token을 넣은 뒤 호출합니다. 문서에서 `/api`로 시작하지 않는 API는 서비스끼리만 호출하므로 이 화면에서는 호출되지 않습니다. 운영 Gateway는 `SPRING_PROFILES_ACTIVE=prod`로 실행합니다. 이 프로파일에서는 서비스 문서 프록시 라우트(`/api-docs/**`), Gateway API 문서와 Swagger UI가 비활성화됩니다. 각 서비스의 직접 접근 문서도 끄려면 해당 서비스에 `SPRINGDOC_API_DOCS_ENABLED=false`, `SPRINGDOC_SWAGGER_UI_ENABLED=false`를 설정합니다. Compose의 `.env`만으로 프로파일이 전달되지는 않으므로 배포 설정에서 Gateway 컨테이너의 환경 변수로 지정해야 합니다.

```powershell
.\scripts\health-check.ps1
```

- Gateway 상태: <http://localhost:8080/actuator/health>
- Eureka 대시보드: <http://localhost:8761>
- LocalStack 상태: <http://localhost:4566/_localstack/health>

Gateway를 통한 예시 호출:

```bash
curl http://localhost:8080/api-docs/user-service
curl http://localhost:8080/api/products
curl http://localhost:8080/api/v1/recommendations/status
```

## 기존 사용자 DB 업그레이드

기존 DB를 재사용하면 `user_service.users`의 email과 nickname 유니크 제약 이름을 각각 `uk_users_email`, `uk_users_nickname`으로 변경한 뒤 시작합니다. 이전 자동 생성 이름의 제약이 남으면 동시 가입 시 중복 계정 오류로 분류되지 않을 수 있습니다. 신규 DB에는 JPA가 명시한 이름으로 제약을 생성합니다.

성별 코드가 `W`/`M`에서 `F`/`M`으로 바뀌었습니다. 예전 DB에는 `gender`에 `W`/`M`만 허용하는 검사 제약이 남아 있어 `F`를 저장하지 못하므로, 기존 DB를 재사용하면 아래를 한 번 실행합니다. 체형(`body_type`) 컬럼은 더 이상 쓰지 않으며 남아 있어도 동작에는 영향이 없습니다.

```sql
ALTER TABLE user_service.user_body DROP CONSTRAINT IF EXISTS user_body_gender_check;
UPDATE user_service.user_body SET gender = 'F' WHERE gender = 'W';
```

## 로컬 빌드

Java 21이 필요합니다. 별도 Gradle 설치 없이 Wrapper를 사용합니다.

product-service를 직접 실행할 때는 DB 비밀번호에 맞는 `POSTGRES_PASSWORD` 환경 변수를 설정해야 합니다. 애플리케이션에는 기본 비밀번호가 없으며, `.env` 파일은 직접 실행 시 자동으로 로드되지 않습니다.

```bash
./gradlew test
```

FastAPI 서비스는 각 디렉터리의 `requirements.txt`로 별도 설치할 수 있습니다.

## 디렉터리

```text
config-service/          Spring Cloud Config (native repository)
discovery-service/       Netflix Eureka
gateway-service/         Spring Cloud Gateway
user-service/            사용자 도메인
product-service/         상품 도메인
order-service/           주문 도메인
community-service/       커뮤니티 도메인
review-service/          리뷰 도메인
notification-service/    알림 도메인
virtual-tryon-service/   FastAPI 가상 피팅 작업 API
recommendation-service/  FastAPI 추천 API
infra/                   PostgreSQL 초기화
scripts/                 운영 보조 스크립트
```

## 구현 원칙

- 서비스별 데이터 소유권을 전제로 PostgreSQL 스키마를 분리했습니다.
- 비동기 연동의 개발 환경은 LocalStack의 SQS/S3로 대체합니다.
- 운영 비밀값은 저장소에 커밋하지 않고 환경 변수로 주입합니다.
- 인증은 Gateway의 Access Token 검증, Redis에 저장하는 Refresh Token, 로그아웃 시 Access Token 블랙리스트로 구현되어 있습니다. 서비스 간 호출은 서비스별 토큰(`X-Service-Token`)으로 인증하며, 관측성 대시보드는 아직 구성하지 않았습니다.
