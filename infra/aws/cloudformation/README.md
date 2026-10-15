# Fitzza AWS foundation

This directory provisions the first deployable AWS baseline for the approved Fitzza project budget.

## Included resources

- A dedicated VPC across two Availability Zones in `ap-northeast-2`
- Two public and two private subnets
- One public NAT Gateway and one Elastic IP
- An S3 Gateway VPC Endpoint so pipeline traffic does not traverse NAT
- One EKS 1.36 Standard cluster
- One ARM64 `t4g.medium` managed node group with `min=0`, `desired=0`, `max=1`
- EKS managed add-ons for VPC CNI, CoreDNS, kube-proxy, Pod Identity, and EBS CSI
- A private, encrypted, versioned S3 bucket for crawler and AI pipeline data
- Immutable ECR repositories for the 11 services, Crawler, and FashionCLIP

The single NAT Gateway is a temporary cost decision for this project. It is a single-AZ outbound dependency and must be reconsidered before production use.

## Cost controls

- Deploy only in `ap-northeast-2`, which is the approved estimate region.
- The node group starts at zero. Scale it to one only during the approved operating window.
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
  --nodegroup-name fitzza-prod-arm64 `
  --scaling-config minSize=0,maxSize=1,desiredSize=1 `
  --profile fitzza `
  --region ap-northeast-2
```

Return `desiredSize` to zero after the approved work window.

## Teardown

Stack deletion stops EKS, EC2, NAT Gateway, Elastic IP, and other stack-managed charges. The data bucket is intentionally retained and must be reviewed separately.

```powershell
aws cloudformation delete-stack `
  --stack-name fitzza-prod-foundation `
  --profile fitzza `
  --region ap-northeast-2
```

After deletion completes, verify that no NAT Gateway, Elastic IP, EKS cluster, load balancer, RDS instance, or EBS volume remains.
