# Fitzza AWS foundation

This directory provisions the first deployable AWS baseline for the approved Fitzza project budget.

## Included resources

- A dedicated VPC across two Availability Zones in `ap-northeast-2`
- Two public and two private subnets
- One public NAT Gateway and one Elastic IP
- An S3 Gateway VPC Endpoint for pipeline traffic and ECR image-layer downloads without NAT processing
- Dedicated security groups for the public ALB and private RDS/managed Redis access
- One private, encrypted PostgreSQL 17.6 `db.t4g.micro` instance with a Secrets Manager-managed master password
- One EKS 1.36 Standard cluster
- One ARM64 `t4g.medium` managed node group with `min=0`, `desired=0`, `max=1`
- The sole worker uses the AZ-c private subnet so the retained Qdrant EBS volume remains attachable when scaled to one node.
- EventBridge Scheduler and Lambda automation for weekday 09:00 scale-up and 18:00 scale-down (`Asia/Seoul`), with scale-up blocked on weekends and configured exclusion dates
- EKS managed add-ons for VPC CNI, CoreDNS, kube-proxy, Pod Identity, and EBS CSI
- An EKS Pod Identity role for AWS Load Balancer Controller v3.6.0
- A private, encrypted, versioned S3 bucket for crawler and AI pipeline data
- Immutable ECR repositories for the 11 services, Crawler, and FashionCLIP

The single NAT Gateway is a temporary cost decision for this project. It is a single-AZ outbound dependency and must be reconsidered before production use.

The ALB itself is created from `infra/k8s/base/ingress.yaml` by AWS Load Balancer Controller. Replace `CHANGE_ME_ALB_SECURITY_GROUP_ID` with the stack output `AlbSecurityGroupId`. The controller manages the matching backend rule for pod targets; do not attach the public ALB security group to EKS nodes or data stores.

RDS is deployed in the private subnets with `PubliclyAccessible=false`. Use the stack outputs `RdsEndpointAddress` and `RdsMasterSecretArn` when preparing the production Kubernetes Secret. The current Redis deployment is exposed only as a Kubernetes `ClusterIP`; `ManagedRedisSecurityGroupId` is reserved for a future managed Redis migration.

## Install the AWS Load Balancer Controller

The foundation stack creates the IAM policy, Pod Identity role, and association for service account `kube-system/aws-load-balancer-controller`. Install the pinned controller only after the stack update completes. Pass the region and VPC explicitly so the Pod never needs EC2 instance metadata.

```powershell
helm repo add eks https://aws.github.io/eks-charts
helm repo update eks
helm upgrade --install aws-load-balancer-controller eks/aws-load-balancer-controller `
  --namespace kube-system `
  --version 3.6.0 `
  --set clusterName=fitzza-prod `
  --set region=ap-northeast-2 `
  --set vpcId=<VpcId stack output> `
  --set serviceAccount.create=true `
  --set serviceAccount.name=aws-load-balancer-controller

kubectl -n kube-system rollout status deployment/aws-load-balancer-controller --timeout=300s
kubectl get ingressclass alb
```

## Cost controls

- Deploy only in `ap-northeast-2`, which is the approved estimate region.
- The node group starts at zero. Scale it to one only during the approved operating window.
- Scale-up is blocked on weekends and on the `NodeScaleUpBlockedDates` dates. The default exclusions are `2026-10-19` and `2026-10-26` in `Asia/Seoul`.
- The EKS control plane and NAT Gateway charge continuously while the stack exists.
- The S3 Gateway Endpoint avoids NAT processing charges for S3 traffic.
- Delete the stack after the project, then manually review retained S3 data.
- ECR repositories use `EmptyOnDelete` so stack deletion can remove build images.
- The data bucket is retained on stack deletion to prevent accidental data loss.

## Validate

Use a real public `/32` address only for deployment. The address in `parameters.example.json` is documentation-only.

```powershell
aws cloudformation validate-template `
  --template-body file://infra/aws/cloudformation/foundation.yaml `
  --profile fitzza `
  --region ap-northeast-2
```

## Preview and deploy

First obtain the current operator public IPv4 address and append `/32`. Do not use `0.0.0.0/0`.

```powershell
$operatorCidr = "203.0.113.10/32"

aws cloudformation deploy `
  --stack-name fitzza-prod-foundation `
  --template-file infra/aws/cloudformation/foundation.yaml `
  --capabilities CAPABILITY_IAM CAPABILITY_AUTO_EXPAND `
  --parameter-overrides `
    ProjectName=fitzza `
    Environment=prod `
    Owner=team3 `
    CostCenter=lgcns5-retail `
    KubernetesVersion=1.36 `
    NodeScaleUpBlockedDates=2026-10-19,2026-10-26 `
    ClusterPublicAccessCidrs=$operatorCidr `
  --tags Project=fitzza Environment=prod Owner=team3 CostCenter=lgcns5-retail `
  --no-execute-changeset `
  --profile fitzza `
  --region ap-northeast-2
```

Review the generated change set in CloudFormation. Execute it only after confirming that it creates one EKS control plane, one NAT Gateway, one Elastic IP, and one scale-to-zero node group.

```powershell
aws cloudformation execute-change-set `
  --stack-name fitzza-prod-foundation `
  --change-set-name <generated-change-set-name> `
  --profile fitzza `
  --region ap-northeast-2
```

After stack completion:

```powershell
aws eks update-kubeconfig `
  --name fitzza-prod `
  --profile fitzza `
  --region ap-northeast-2

aws eks update-nodegroup-config `
  --cluster-name fitzza-prod `
  --nodegroup-name fitzza-prod-arm64-c `
  --scaling-config minSize=0,maxSize=1,desiredSize=1 `
  --profile fitzza `
  --region ap-northeast-2
```

Return `desiredSize` to zero after the approved work window.

## Verify scheduled scaling

The schedules invoke a least-privilege Lambda that accepts only `desiredSize` 0 or 1. Scale-up requests are skipped on `Asia/Seoul` weekends and on dates configured by `NodeScaleUpBlockedDates`; scale-down remains allowed every day. Test scale-up during the approved work window:

```powershell
aws lambda invoke `
  --function-name fitzza-prod-node-scaler `
  --payload '{"desiredSize":1}' `
  --cli-binary-format raw-in-base64-out `
  --profile fitzza `
  --region ap-northeast-2 `
  node-scaler-result.json

aws eks describe-nodegroup `
  --cluster-name fitzza-prod `
  --nodegroup-name fitzza-prod-arm64-c `
  --query 'nodegroup.scalingConfig' `
  --profile fitzza `
  --region ap-northeast-2
```

After verifying that one node becomes `Ready`, invoke the function with `{"desiredSize":0}` or wait for the weekday 18:00 schedule. Never leave the node running outside the approved window.

## Teardown

Stack deletion stops EKS, EC2, NAT Gateway, Elastic IP, and other stack-managed charges. The data bucket is intentionally retained and must be reviewed separately.

```powershell
aws cloudformation delete-stack `
  --stack-name fitzza-prod-foundation `
  --profile fitzza `
  --region ap-northeast-2
```

After deletion completes, verify that no NAT Gateway, Elastic IP, EKS cluster, load balancer, RDS instance, or EBS volume remains.
