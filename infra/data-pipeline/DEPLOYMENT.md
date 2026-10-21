# 배포 상태와 실행 분리

기준: 2026-10-21 KST. 이 문서의 배포는 실제 크롤링·Jev 호출·임베딩·적재를 승인하지 않는다.
새 Job은 모두 `suspend: true`이며 `--execute-approved-run`도 의도적으로 빠져 있다.
정지만 해제해도 데이터 작업은 실행되지 않는다. 자동 CronJob/EventBridge 실행은 구성하지 않는다.

## 구성

| 단계 | 준비 리소스 | 다음 단계 조건 |
| --- | --- | --- |
| 신규 수집 | crawler-collection-prepared / src.cloud_batch | 500개 seed ID 제외, 실행별 체크포인트 |
| 데이터 품질·선정 | 별도 검증 및 curated 번들 게시 필요 | 3000개 고유 상품·목표 비율·실측·색상별 이미지 검증 |
| Jev 색상 정제 | jev-color-review-prepared / src.jev_job | SSM 키 등록 및 curated/bundle.tar.gz |
| 사람 검수 | 로컬 review-bundle / src.review_gate | 미확정 색상 및 기존 overrides의 명시적 재검수 |
| 인덱싱 | fashion-clip-indexer-prepared / indexer/entrypoint.py | 색상 승인 + 품질 승인 + 모델 revision 고정 |

기존 500개는 카테고리당 100개·중복 0개다. 모두 품질 검증을 통과한다면 추가 목표는
상의 800 / 하의 650 / 신발 500 / 아우터 350 / 모자 200이다. seed manifest는 수량·hash 목록이며 품질 승인본이 아니다.
raw batch 성공과 screened 수량을 최종 합격 상품 수로 간주하지 않는다.

## 소스 및 이미지

- 크롤러: 이 브랜치 `crawler/src`; Python 3.12, Playwright 1.55.0, ARM64.
- Jev·인덱서 기준: `2kmkmkm/fashion-CLIP`, `feat/color-map-jev`, commit `1568de39e17cde9fb229b30f78b53f80b2a891f5`.
- `feat/docker-qdrant-data`의 인덱서는 동일하다. 해당 Dockerfile의 스냅샷 자동 복구는 기존 EKS Qdrant에 적용하지 않는다.
- 배포 어댑터는 기존 컬렉션 삭제 금지, staging 전용, 4개 이미지 단위 임베딩, 승인 확인을 추가했다.
- 모델 런타임 기반은 운영 recommendation-service digest `sha256:d9ae634c8119cb5e5e36ed4d1311115c350f2519c25bba6e03737ccd704b3c7b`다.
- 모델 가중치는 빌드·배포 중 내려받거나 실행하지 않는다. 실제 인덱싱 시 품질 승인 manifest에 지정한 모델 commit SHA를 사용한다.

## S3 계약

버킷: `fitzza-prod-foundation-databucket-wwng6wfc0hqy`
다음 실행 prefix: `pipeline/runs/collection-20261022-01/` (날짜는 식별자이며 예약 실행이 아님).

```text
seed/manifest.json                # seed 후보 ID·hash
crawler/objects/<sha256>           # raw 체크포인트, 내용 주소 기반
crawler/checkpoints.json           # 경로→hash 목록, ETag 조건부 게시
curated/bundle.tar.gz              # 품질·선정 후 data/**/*.json을 운영자가 게시
jev/color_map_jev_results.jsonl    # 재사용 가능한 Jev 응답
jev/review-bundle.tar.gz           # data/ + config/, 사람 검수 다운로드
approved/bundle.tar.gz             # 사람이 검수하고 품질 검증한 승인본
indexer/report.json                # 적재 후 보고서, 서빙 전환 아님
```

현재 raw→curated 자동 선정·색상 이미지 품질 검증과 전체 단계 오케스트레이션은 완료되지 않았다.
FITZZA-219 완료나 전체 end-to-end 검증 완료로 기록하지 않는다. 운영자 입력 없이 raw를 Jev에 바로 넘기지 않는다.
checkpoint는 같은 실행에서 직렬 Job으로만 갱신한다. ETag 충돌은 실패이며 강제로 덮어쓰지 않는다.
새 worker는 이전 pilot 경로를 자동 합치지 않는다.

## 키 등록 (사용자가 직접, 실제 호출과 별도)

일반 대화·파일·명령행 인자에 키를 붙여 넣지 않는다. 대화형 PowerShell에서 아래를 실행하고 숨김 프롬프트에 입력한다.

```powershell
C:\inspire\final-project\fashion-crawler-starter\.venv\Scripts\python.exe C:\Users\user\.codex\worktrees\crawler-cloud-pipeline\backend_project\infra\data-pipeline\register_jev_key.py
```

스크립트는 `fitzza` 프로필로 `/fitzza/prod/data-pipeline/typesafe-api-key`를 Standard SecureString으로 새로 등록한다.
기존 값은 덮어쓰지 않으며 키를 출력하지 않는다. 이 스크립트는 배포 과정에서 자동 실행하지 않는다.
Jev Job은 실행 승인을 받은 경우에만 해당 파라미터를 복호화해 자식 프로세스에 전달한다.
Secrets Manager 자원은 새로 만들지 않는다.

## 사람 검수와 승인

검수 번들에 `data/`와 `config/`가 있어야 한다. `color_map_review.csv`의 모든 항목과 적용된 기존 overrides를 확인한다.
최종 선택을 `color_map.json`에 반영하고, 직접 확인한 색상의 `{정규화된 원래 이름: 최종 색상}`을 `reviewed_colors.json`에 기록한다.
기존 overrides 파일 존재만으로 검수 완료로 간주하지 않는다.

```powershell
python crawler/src/review_gate.py approve --bundle <검수한-번들-폴더>
python crawler/src/review_gate.py verify --bundle <검수한-번들-폴더>
```

이 명령은 색상 승인만 생성한다. 실측·이미지·비율·3000개 품질 검증을 대신하지 않는다.
별도 품질 검증 후 `config/_QUALITY_APPROVED.json`에 `approved: true`, 같은 파일 목록/hash(`files`),
FashionCLIP 모델의 40자리 commit SHA(`model_revision`)를 기록한다. 이 파일을 임의로 만들거나 검증 없이 true로 설정하지 않는다.
색상 승인 후 데이터·매핑·검수 결과 변경 시 승인을 다시 받아야 한다. 승인본 게시 권한은 Job에 부여하지 않는다.
인덱서는 3000개·승인 fingerprint 일치·고정 모델 revision이 없으면 Qdrant 연결 전에 실패한다.

## 운영 및 후속

- 오늘 추가 서비스 중단·수집·Jev 호출·임베딩·적재는 하지 않는다. 내일도 별도 실행 지시 전까지 정지 상태 유지.
- 실행 전 단일 노드 용량, 유지보수 대상과 원래 replica/CD 상태, 업무시간 09~18시를 다시 확인한다.
- 기존 pilot 복구 스크립트는 10/21 전용 기록이며 새로운 전체 수집 실행기로 사용하지 않는다.
- staging 컬렉션만 생성한다. 운영 컬렉션·PVC·추천 서비스 연결·alias는 자동 변경하지 않는다.
- 실패한 staging 컬렉션은 자동 삭제하지 않는다. 동일 이름이 있으면 실패하므로 재시도 시 새 staging 실행 식별자가 필요하다.
- 각 Job의 IAM은 입력 읽기와 해당 단계 결과 쓰기로 분리한다. 앱·CD 역할은 변경하지 않는다.
- IAM 정책 재검토 시 정적 분석: `uvx iam-policy-autopilot@latest generate-policies <runtime-source-absolute-path> --region ap-northeast-2 --account 414901227428 --service-hints s3 ssm --pretty`.
- 빌드·네트워크 차단 테스트·서버 dry-run은 실행 검증과 구분한다. 실제 모델 추론과 3000개 적재 검증은 아직 하지 않았다.

## 앞선 실행 이력 (새 실행 승인 아님)

10/21에 요청 해석 오류로 pilot에서 상의 후보 80개·상세 5개를 수집했다. 결과는 별도 `crawler-pilot-20261021-01` prefix에 보존했다.
pilot은 종료·정지됐고 두 AI 앱의 replicas 및 CD 전환은 복구했다. 이 이력을 전체 수집 완료로 집계하지 않는다.
