# 2026-10-21 배포 증적

실제 데이터 작업은 별도 승인 전 실행하지 않는다. 모든 준비 Job은 suspend=true이고 실행 승인 옵션이 없다.

| Job | ECR 저장소 / 태그 | 이미지 digest |
| --- | --- | --- |
| crawler-collection-prepared | fitzza/crawler:prepared-20261021-01 | sha256:cd92b5cec20c5ca8e40c824574557e9661be9df429c23eca16791bac4f3e005d |
| jev-color-review-prepared | fitzza/crawler:jev-prepared-20261021-01 | sha256:e8ea48cf9e85aefd1ee41c5de78f015d7d0de29883a93152a78760e2b2500935 |
| fashion-clip-indexer-prepared | fitzza/fashion-clip:prepared-20261021-02 | sha256:b759c67af6881e8f010a255738d3b3e972d6ba6875c438ff5cbaefe257c4451c |

계정 414901227428 / 서울 리전 / EKS fitzza-prod / namespace fitzza.

서비스 계정과 전용 IAM 역할:

- fitzza-crawler → fitzza-prod-crawler-pilot (다음 실행 경로용 제한 정책 추가)
- fitzza-jev → fitzza-prod-jev (지정된 SSM 파라미터 하나와 해당 실행 Jev 경로)
- fitzza-indexer → fitzza-prod-indexer (승인 번들 읽기·보고서 쓰기만 허용)

각 역할은 EKS cluster/namespace/service-account session tag로 제한한다.
Access Analyzer 정책 findings=0. IAM 시뮬레이터에서 승인본 읽기 허용·승인본 덮어쓰기 거부,
크롤러 경로 쓰기 허용·외부 경로/삭제 거부와 Jev 지정 파라미터 읽기 허용을 확인했다.
새 Jev/인덱서 Pod의 실제 AWS 호출은 실행하지 않았다.

검증:

- 로컬 unit tests 34건 통과 (crawler 17 / 배포·복구·인덱서 계약·키 등록 17).
- 네트워크 차단 ARM64 컨테이너에서 세 단계의 deployment_only 모드 통과.
- Jev --help, Chromium 시작·종료 확인. 모델 생성·추론은 실행하지 않음.
- 인덱서 runtime imports: torch 2.14.0+cu130 / transformers 5.17.0 / requests 2.32.5.
- EKS 서버 dry-run 통과. 실제 준비 Job 활성 Pod 0개.
- 기존 추천·가상피팅 앱 1/1, Redis 1/1, Qdrant PVC UID 90f1a70b-702d-4e42-97cc-8f69d29c3d95 유지.

미완료: 사용자 키의 SSM 등록, raw→curated 품질·선정/번들 게시 연결,
사람 검수 승인본, 모델 revision 결정 및 단계별 실제 end-to-end 검증.
이는 전체 크롤링 파이프라인의 완전 자동화나 3000개 적재 완료 증거가 아니다.
