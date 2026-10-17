from pathlib import Path

import pytest

from scripts.render_production_manifests import render


def write_manifests(root: Path) -> None:
    base = root / "infra/k8s/base"
    base.mkdir(parents=True)
    (base / "configmap.yaml").write_text(
        "POSTGRES_HOST: CHANGE_ME_RDS_ENDPOINT\n"
        "CORS_ALLOWED_ORIGINS: CHANGE_ME_FRONTEND_ORIGIN\n",
        encoding="utf-8",
    )
    (base / "ingress.yaml").write_text(
        "metadata:\n"
        "  annotations:\n"
        "    alb.ingress.kubernetes.io/listen-ports: '[{\"HTTP\": 80}, {\"HTTPS\": 443}]'\n"
        "    alb.ingress.kubernetes.io/ssl-redirect: \"443\"\n"
        "    alb.ingress.kubernetes.io/security-groups: CHANGE_ME_ALB_SECURITY_GROUP_ID\n"
        "    alb.ingress.kubernetes.io/certificate-arn: CHANGE_ME_ACM_CERTIFICATE_ARN\n"
        "spec:\n"
        "  rules:\n"
        "    - host: CHANGE_ME_API_DOMAIN\n"
        "      http: {}\n",
        encoding="utf-8",
    )


def test_render_http_only(tmp_path: Path) -> None:
    write_manifests(tmp_path)

    render(
        root=tmp_path,
        rds_endpoint="fitzza.abc.ap-northeast-2.rds.amazonaws.com",
        frontend_origin="https://app.example.com/",
        alb_security_group_id="sg-0123456789abcdef0",
    )

    configmap = (tmp_path / "infra/k8s/base/configmap.yaml").read_text()
    ingress = (tmp_path / "infra/k8s/base/ingress.yaml").read_text()
    assert "fitzza.abc.ap-northeast-2.rds.amazonaws.com" in configmap
    assert "https://app.example.com" in configmap
    assert 'listen-ports: \'[{"HTTP": 80}]\'' in ingress
    assert "ssl-redirect" not in ingress
    assert "certificate-arn" not in ingress
    assert "host:" not in ingress
    assert "CHANGE_ME_" not in configmap + ingress


def test_render_https(tmp_path: Path) -> None:
    write_manifests(tmp_path)

    render(
        root=tmp_path,
        rds_endpoint="fitzza.abc.ap-northeast-2.rds.amazonaws.com",
        frontend_origin="https://app.example.com",
        alb_security_group_id="sg-0123456789abcdef0",
        api_domain="api.example.com",
        acm_certificate_arn=(
            "arn:aws:acm:ap-northeast-2:123456789012:certificate/"
            "12345678-1234-1234-1234-123456789012"
        ),
    )

    ingress = (tmp_path / "infra/k8s/base/ingress.yaml").read_text()
    assert "api.example.com" in ingress
    assert "arn:aws:acm:ap-northeast-2:123456789012:certificate/" in ingress
    assert "ssl-redirect" in ingress
    assert "CHANGE_ME_" not in ingress


def test_render_rejects_partial_tls_configuration(tmp_path: Path) -> None:
    write_manifests(tmp_path)

    with pytest.raises(ValueError, match="supplied together"):
        render(
            root=tmp_path,
            rds_endpoint="fitzza.abc.ap-northeast-2.rds.amazonaws.com",
            frontend_origin="https://app.example.com",
            alb_security_group_id="sg-0123456789abcdef0",
            api_domain="api.example.com",
        )


def test_render_failure_preserves_both_inputs(tmp_path: Path) -> None:
    write_manifests(tmp_path)
    base = tmp_path / "infra/k8s/base"
    ingress = base / "ingress.yaml"
    ingress.write_text(ingress.read_text() + "unknown: CHANGE_ME_UNKNOWN\n")
    before = {path: path.read_bytes() for path in base.iterdir()}
    with pytest.raises(ValueError, match="unresolved"):
        render(tmp_path, "db.example.com", "https://app.example.com", "sg-0123456789abcdef0")
    assert all(path.read_bytes() == content for path, content in before.items())


def test_frontend_path_rejected_without_writing(tmp_path: Path) -> None:
    write_manifests(tmp_path)
    base = tmp_path / "infra/k8s/base"
    before = {path: path.read_bytes() for path in base.iterdir()}
    with pytest.raises(ValueError, match="must not include a path"):
        render(tmp_path, "db.example.com", "https://app.example.com/login", "sg-0123456789abcdef0")
    assert all(path.read_bytes() == content for path, content in before.items())
