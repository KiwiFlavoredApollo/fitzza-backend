# 2026-10-21 배포 증적

실제 데이터 작업은 별도 승인 전 실행하지 않는다. 모든 준비 Job은 suspend=true이고 실행 승인 옵션이 없다.

| Job | ECR 저장소 / 태그 | 이미지 digest |
| --- | --- | --- |
| crawler-collection-prepared | fitzza/crawler:prepared-20261021-01 | sha256:cd92b5cec20c5ca8e40c824574557e9661be9df429c23eca16791bac4f3e005d |
| data-curation-prepared | fitzza/crawler:curate-prepared-20261021-01 | sha256:ec9fb24dca25a93138a182d339dd5420ce229dd3d4d6b19307307738962a3e90 |
| jev-color-review-prepared | fitzza/crawler:jev-prepared-20261021-01 | sha256:e8ea48cf9e85aefd1ee41c5de78f015d7d0de29883a93152a78760e2b2500935 |
| fashion-clip-indexer-prepared | fitzza/fashion-clip:prepared-20261021-02 | sha256:b759c67af6881e8f010a255738d3b3e972d6ba6875c438ff5cbaefe257c4451c |

계정 414901227428 / 서울 리전 / EKS fitzza-prod / namespace fitzza.

서비스 계정과 전용 IAM 역할:

- fitzza-crawler → fitzza-prod-crawler-pilot (다음 실행 경로용 제한 정책 추가)
- fitzza-curator → fitzza-prod-curator (이번 실행 seed/체크포인트 읽기·curated 최초 생성만 허용)
- fitzza-jev → fitzza-prod-jev (지정된 SSM 파라미터 하나와 해당 실행 Jev 경로)
- fitzza-indexer → fitzza-prod-indexer (승인 번들 읽기·보고서 쓰기만 허용)

각 역할은 EKS cluster/namespace/service-account session tag로 제한한다.
기존 세 역할의 Access Analyzer 정책 findings=0. 새 curator는 literal `*` 비교에 관한 경고 1건을 문서화했고
create-only 조건을 완화하지 않았다. 시뮬레이터에서 조건부 Put 허용·헤더 없는 Put/Delete/SSM 읽기 거부 확인.
기존 IAM 시뮬레이터에서 승인본 읽기 허용·승인본 덮어쓰기 거부,
크롤러 경로 쓰기 허용·외부 경로/삭제 거부와 Jev 지정 파라미터 읽기 허용을 확인했다.
새 Jev/인덱서 Pod의 실제 AWS 호출은 실행하지 않았다.

검증:

- 로컬 unit tests 52건 통과 (crawler 35 / 배포·복구·인덱서 계약·키 등록 17).
- 합성 3000개 fixture로 카테고리 비율·계절 배정·Jev 번들 레이아웃 확인. 실제 수집 데이터 적재 시험은 아님.
- 네트워크 차단 ARM64 컨테이너에서 세 단계의 deployment_only 모드 통과.
- Jev --help, Chromium 시작·종료 확인. 모델 생성·추론은 실행하지 않음.
- 인덱서 runtime imports: torch 2.14.0+cu130 / transformers 5.17.0 / requests 2.32.5.
- EKS 서버 dry-run 통과. 실제 준비 Job 활성 Pod 0개.
- 정제 ARM64 컨테이너를 --network none으로 실행하여 deployment_only network_calls=0 확인.
- 17:35 재확인: 신규 네 단계 정지, 기존 pilot 정지, 정제 Pod 0개, 기존 앱/Redis 12개 배포 모두 1/1, CD Deploy inbound=true.
- 기존 추천·가상피팅 앱 1/1, Redis 1/1, Qdrant PVC UID 90f1a70b-702d-4e42-97cc-8f69d29c3d95 유지.

SSM 키는 사용자 등록 완료, describe-parameters로 SecureString version 1만 확인(값 조회 없음).
10/22 후속 코드(아직 ECR/Job 미반영): COLOR_CHIP 기반 color_image_mappings 생성 및 상품 ID 연계 보완.
오프라인 unit test 61개 통과(crawler 44 / 배포·복구·인덱서 계약·키 등록 17).
저장 HTML 3개는 동일 상품의 서로 다른 저장본이며, 각각 명시적 이미지 연결 6개 확인(품질/도달성 검증 아님).
미완료: OtherColorGoods 원본 fixture 기반 추가 경로, 새 크롤러 이미지 반영, seed 본문 객체 게시, 실제 이미지 내용 검증,
사람 검수 승인본, 모델 revision 결정 및 단계별 실제 end-to-end 검증.
기존 500개 오프라인 실측 계약 검사에서는 488개 통과·12개 미달. 원본은 변경하지 않았다.
이는 전체 크롤링 파이프라인의 완전 자동화나 3000개 적재 완료 증거가 아니다.
