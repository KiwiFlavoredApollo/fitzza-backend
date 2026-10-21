import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]


def test_delivery_template_does_not_grant_cluster_admin():
    template = (ROOT / "infra/aws/cloudformation/delivery.yaml").read_text()
    assert "AmazonEKSClusterAdminPolicy" not in template
    assert "KubernetesGroups: [fitzza-delivery]" in template
    spec = (ROOT / "deployspec.yml").read_text()
    assert "overlays/delivery" in spec
    assert "get secret" not in spec


def test_artifact_endpoint_is_opt_in_and_account_scoped():
    template = (ROOT / "infra/aws/cloudformation/foundation.yaml").read_text()
    assert "HasDeliveryArtifactBucket: !Not" in template
    assert "aws:PrincipalAccount: !Ref AWS::AccountId" in template
    assert "s3:::${DeliveryArtifactBucketName}/*" in template


@pytest.mark.skipif(shutil.which("kubectl") is None, reason="kubectl unavailable")
def test_delivery_overlay_excludes_operator_owned_resources():
    yaml = pytest.importorskip("yaml")
    result = subprocess.run(
        ["kubectl", "kustomize", str(ROOT / "infra/k8s/overlays/delivery")],
        check=True, capture_output=True, text=True, timeout=30,
    )
    documents = list(yaml.safe_load_all(result.stdout))
    assert len([d for d in documents if d["kind"] == "Deployment"]) == 11
    assert all(d["kind"] in {"Deployment", "Service", "ConfigMap", "Ingress"}
               for d in documents)
    assert all(d["metadata"]["namespace"] == "fitzza" for d in documents)
    assert not any(d["kind"] == "Deployment" and d["metadata"]["name"] == "redis"
                   for d in documents)


def test_delivery_role_has_no_secret_storage_or_rbac_mutation():
    yaml = pytest.importorskip("yaml")
    role, binding = yaml.safe_load_all(
        (ROOT / "infra/k8s/bootstrap/delivery-rbac.yaml").read_text()
    )
    assert role["metadata"]["namespace"] == binding["metadata"]["namespace"] == "fitzza"
    for rule in role["rules"]:
        assert "*" not in rule["verbs"] + rule["resources"] + rule["apiGroups"]
        assert "secrets" not in rule["resources"]
        assert "rbac.authorization.k8s.io" not in rule["apiGroups"]
        if set(rule["resources"]) & {"persistentvolumeclaims", "statefulsets"}:
            assert set(rule["verbs"]) <= {"get", "list", "watch"}
