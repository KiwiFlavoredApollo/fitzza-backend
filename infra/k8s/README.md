# Fitzza EKS deployment

This directory contains the local, credential-free Kubernetes deployment baseline.

## Prerequisites

- An EKS cluster with the AWS Load Balancer Controller installed
- ECR repositories for every application image
- An RDS PostgreSQL endpoint reachable from the private subnets
- An IAM association for the `fitzza-application` service account using EKS Pod Identity or IRSA
- A Kubernetes Secret named `fitzza-app-secrets`, populated from AWS Secrets Manager in the deployed environment

Do not apply `base/secret.example.yaml`. It documents required keys only and is intentionally excluded from Kustomize.

## Values to replace

Before deployment, replace the following placeholders through an environment-specific overlay or pipeline step:

- `CHANGE_ME_RDS_ENDPOINT`
- `CHANGE_ME_FRONTEND_ORIGIN`
- `CHANGE_ME_ACM_CERTIFICATE_ARN`
- `CHANGE_ME_API_DOMAIN`
- all `fitzza/<service>:latest` image references with ECR image URIs pinned to an immutable commit tag

## Local validation

```powershell
kubectl kustomize infra/k8s/overlays/prod
```

## Deployment order

1. Create the namespace and synchronized secret.
2. Deploy Redis, Config Server, and Discovery Service.
3. Deploy domain services and Python services.
4. Deploy Gateway and the ALB Ingress.
5. Verify every `/health` endpoint before routing production traffic.

