# Fitzza EKS deployment

This directory contains the local, credential-free Kubernetes deployment baseline.

## Prerequisites

- An EKS cluster with the AWS Load Balancer Controller installed
- ECR repositories for every application image, with tag immutability enabled
- An RDS PostgreSQL endpoint reachable from the private subnets
- An IAM association for the `fitzza-application` service account using EKS Pod Identity or IRSA
- A Kubernetes Secret named `fitzza-app-secrets`, populated from the approved secret store. The SSM transition is tracked in FITZZA-251; the existing RDS password still uses Secrets Manager until that transition is verified.

Do not apply `base/secret.example.yaml`. It documents required keys only and is intentionally excluded from Kustomize.

Use `python scripts/validate_app_secrets.py` with JSON on stdin to validate all 12 keys without printing values or contacting AWS. The optional `--emit-manifest` writes sensitive JSON for direct piping to kubectl only. See the [pilot deployment runbook](../aws/cloudformation/PILOT_DEPLOYMENT.md) for the SSM transition, operating restrictions, and validation order.

## Values to replace

Before deployment, replace the following placeholders through an environment-specific overlay or pipeline step:

- `CHANGE_ME_RDS_ENDPOINT`
- `CHANGE_ME_FRONTEND_ORIGIN`
- `CHANGE_ME_ACM_CERTIFICATE_ARN`
- `CHANGE_ME_ALB_SECURITY_GROUP_ID` (CloudFormation output `AlbSecurityGroupId`)
- `CHANGE_ME_API_DOMAIN`

The production overlay contains image mappings for all 11 application services. CodeBuild's `post_build` step fills `CHANGE_ME_ECR_REGISTRY`, `CHANGE_ME_ECR_REPOSITORY_PREFIX`, and `CHANGE_ME_COMMIT_TAG` from `image-definitions.json`, using the same ECR paths and 12-character Git commit tag as the image build. CodeBuild requires a resolved Git commit SHA; there is no `local` tag fallback.

Deploy the production overlay from that build's artifact, which includes the populated mappings. When preparing an overlay manually, replace these three image placeholders with the registry (for example, `123456789012.dkr.ecr.ap-northeast-2.amazonaws.com`), repository prefix (default `fitzza`), and commit tag of the images actually pushed to ECR before rendering or applying it.

## Local validation

```powershell
kubectl kustomize infra/k8s/overlays/prod
```

## Deployment order

The production overlay explicitly sets `SPRING_PROFILES_ACTIVE=native,prod` for Config Server. Its classpath configuration backend requires `native`; the shared ConfigMap's `prod` profile alone would disable that backend. Other services retain the shared `prod` profile.

1. Create the namespace and synchronized secret.
2. Deploy Redis, Config Server, and Discovery Service.
3. Deploy domain services and Python services.
4. Deploy Gateway and the ALB Ingress.
5. Verify every `/health` endpoint before routing production traffic.

The ALB uses the dedicated CloudFormation security group and lets AWS Load Balancer Controller manage only the backend rule required for pod targets. RDS must use the `RdsSecurityGroupId` output. The current Redis is a private `ClusterIP` service inside EKS; use `ManagedRedisSecurityGroupId` only if it is later replaced with ElastiCache or another VPC-managed Redis service.

Qdrant deployment, collection, persistence, and snapshot recovery procedures are documented in [QDRANT.md](QDRANT.md).

## Redis updates

Redis uses one replica with a `Recreate` update strategy and a shared `ReadWriteOnce` data volume. During an update, the existing Pod terminates before its replacement starts, so Redis is unavailable until the replacement is ready. Schedule Redis updates in a maintenance window and account for failures in Redis-dependent requests during this interruption.

After applying the update, wait for `kubectl -n fitzza rollout status deployment/redis --timeout=300s` to succeed and verify the application health endpoints before routing production traffic again.
