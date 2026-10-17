# 10/21 시범 배포 준비

관련 과업: FITZZA-251(Secret 예산), FITZZA-167(배포), FITZZA-161(네트워크).
이 문서는 실행 기록이 아니라 운영 시간에 수행할 절차다.

## 10/17~19: 로컬 준비

- 주말 및 10/19는 현재 노드 기동 제외일이다. EKS 노드·RDS 기동,
  CloudFormation 적용, CodeBuild/CodePipeline 실행, Secret 전환을 하지 않는다.
- PR 리뷰와 병합 준비, 환경값·Secret 키 계약 검증은 로컬에서 수행한다.
- 기존 RDS는 `ManageMasterUserPassword: true`로 Secrets Manager를 사용한다.
  SSM에 비밀번호를 복사하는 것만으로 기존 Secret 비용이 사라지지 않는다.
- 예산 목표는 SSM Standard SecureString과 기본 `alias/aws/ssm` 사용이다.
  Standard 일반 처리량은 추가 Parameter Store 요금이 없지만 KMS 사용 요금은
  별도 확인한다. Advanced·고처리량·고객 관리형 KMS 키는 추가하지 않는다.
  [AWS 가격](https://aws.amazon.com/systems-manager/pricing/).

## FITZZA-251: 운영 시간에 Secret 전환

1. 기존 DB 상태, Secret 소비자, 자동 회전 및 복구 가능성을 확인한다.
2. foundation의 기본 `DatabasePasswordSource=managed`는 현재 동작을 유지한다.
   운영 시간에 다음 값을 명시한 Change Set을 만들고 실행 전 검토한다.
   - `DatabasePasswordSource=ssm`
   - `DatabasePasswordSsmParameter=/fitzza/prod/db/master-password`
   - `DatabasePasswordSsmVersion=<보존할 실제 정수 버전>`
   비밀번호 전용 SecureString을 먼저 준비한다. 앱 JSON 파라미터를 여기에
   지정하면 JSON 전체가 DB 비밀번호로 설정되므로 사용하지 않는다.
   SSM 모드에서는 `RdsMasterSecretArn` 출력이 제거되고 비밀값 없는
   `RdsPasswordSsmParameter` 경로 출력만 제공된다. 기존 출력 소비자를 확인한다.
   RDS 교체·삭제가 나타나는 Change Set은 실행하지 않는다.
   **관리형 비밀번호를 해제하면 RDS가 관리하던 Secret이 삭제된다.**
   따라서 변경 실행 전에 보호된 복구용 자격 증명을 확보하고 소비자를 파악한다.
   CloudFormation 실행 주체에는 비밀번호 전용 경로의 `ssm:GetParameters`와
   선택한 키에 필요한 복호화 권한이 있어야 한다. 버전을 고정해 암호 회전 시
   템플릿 변경이 발생하게 하고, 롤백에 필요한 이전 SSM 버전을 보존한다.
   [AWS 전환 동작](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/rds-secrets-manager.html).
3. 자격 증명은 승인된 SSM SecureString에 보관한다. 비밀값을 CLI 인자,
   터미널 출력, Git, Jira, 로그, 빌드 아티팩트에 기록하지 않는다.
   애플리케이션 키 계약은 `infra/k8s/base/secret.example.yaml`의 12개 키다.
   하나의 JSON 파라미터 `/fitzza/prod/app-secrets`를 사용할 경우 4 KiB 이하인지 검증한다.
4. 운영자에게 해당 파라미터의 `ssm:GetParameter`만 부여한다.
   사용자 관리형 KMS 키를 선택하면 해당 키의 복호화 권한·예산도 재검토한다.
   빌드 역할에 Secret 읽기 권한을 부여하지 않는다.
5. 아래 검증기를 사용해 값 노출 없이 확인한 뒤 Kubernetes에 동기화한다.
   검증기는 AWS·kubectl을 호출하거나 파일을 생성하지 않는다.
   실제 값은 표준 입력으로만 받는다. 검증만 할 때는 `--emit-manifest`를 생략한다.

   ```bash
   # 승인된 운영 시간, bash에서 실행. set -x 또는 CLI --debug 사용 금지.
   set -euo pipefail
   aws ssm get-parameter --name /fitzza/prod/app-secrets --with-decryption \
     --query Parameter.Value --output text --profile fitzza --region ap-northeast-2 \
     | python scripts/validate_app_secrets.py --emit-manifest \
     | kubectl --context VERIFIED_FITZZA_CONTEXT -n fitzza apply -f -
   ```

   `VERIFIED_FITZZA_CONTEXT`는 확인한 실제 컨텍스트로 대체한다.
   stdout에 Secret을 출력하거나 파일로 저장하지 않는다.
   Redis URL의 비밀번호는 URL 인코딩하고 내부 주소 `redis:6379`를 사용한다.
   recommendation DB는 `/1`, VTON DB는 `/0`이다.
6. DB 인증, Redis 인증, 앱 연결 및 재기동을 확인한다. 회전은 이제 운영자가
   담당하므로 SSM·DB·Kubernetes 값을 함께 갱신하는 절차를 기록한다.
   전환 실패 시 SSM의 이전 값만 복원해 끝내지 말고 실제 DB 비밀번호와
   Kubernetes Secret을 같은 유효 값으로 맞추고 연결을 재검증한다.

## 10/20: 배포·네트워크·복구 검증

1. 위 Secret 전환과 배포 관련 PR 병합 완료 확인.
2. 허용 시간에 노드 1대 Ready 확인. 전체 앱·시스템 Pod requests 및 Pod 수가
   실제 노드 allocatable에 들어가는지 확인하고 부족하면 배포를 멈춘다.
3. 실제 RDS endpoint, private subnet, ALB SG, frontend origin 입력.
   TLS를 쓰면 API 도메인과 인증서를 모두 입력한다.
4. Change Set을 검토한 후 배포 단계 활성화. develop 기준 실행과 모든 rollout 확인.
5. ALB 외부 health 및 핵심 사용자 흐름 확인. RDS 비공개, Redis ClusterIP,
   Pod 외부 통신, NAT·S3 Gateway Endpoint 경로를 확인한다.
6. FITZZA-221 비용 알림 확인. 이전 이미지 버전으로 롤백 후 복구를 시험한다.
7. 커밋·이미지 digest·실행 ID·검증 결과만 Jira에 기록하고 운영 종료 시 노드 0 확인.

## 10/21: 시범 배포

전날 검증한 버전으로 시범 배포한다. 10/20에 연결·용량·롤백 검증이 실패했다면
해당 문제를 먼저 해결하고 시범 배포 완료로 처리하지 않는다.
