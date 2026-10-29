"""Render isolated Kubernetes Job specs for pipeline stages."""

from __future__ import annotations

import re
from typing import Any


def render_job(
    stage: str,
    contract: dict[str, Any],
    image: str,
    command: list[str],
    cpu_request: str = "500m",
    cpu_limit: str = "2",
    memory_request: str = "1Gi",
    memory_limit: str = "4Gi",
) -> dict[str, Any]:
    """Create a deterministic, resource-bounded Job spec for one pipeline stage."""
    if stage not in {"crawler", "fashion-clip", "integrity"}:
        raise ValueError("stage must be crawler, fashion-clip, or integrity")
    run_id = contract["run_id"]
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{2,63}", run_id):
        raise ValueError("invalid run_id")
    if not image or not command:
        raise ValueError("image and command are required")
    name = f"fitzza-{stage}-{run_id}"[:63].rstrip("-")
    labels = {"app.kubernetes.io/part-of": "fitzza-data-pipeline", "fitzza.io/run-id": run_id}
    if stage in {"fashion-clip", "integrity"}:
        labels["fitzza.io/qdrant-client"] = "true"
    env = [
        {"name": "PIPELINE_RUN_ID", "value": run_id},
        {"name": "PIPELINE_RUN_MODE", "value": contract["run_mode"]},
        {"name": "PIPELINE_S3_BUCKET", "value": contract["s3"]["bucket"]},
        {"name": "PIPELINE_S3_PREFIX", "value": contract["s3"]["prefix"]},
        {"name": "PIPELINE_MANIFEST_SHA256", "value": contract["input_manifest_sha256"]},
    ]
    baseline = contract.get("baseline") or {}
    if baseline.get("manifest_key"):
        env.append({"name": "PIPELINE_BASELINE_MANIFEST_KEY", "value": baseline["manifest_key"]})
    return {
        "apiVersion": "batch/v1",
        "kind": "Job",
        "metadata": {"name": name, "labels": labels},
        "spec": {
            "backoffLimit": 0,
            "ttlSecondsAfterFinished": 86400,
            "activeDeadlineSeconds": 21600,
            "template": {
                "metadata": {"labels": labels},
                "spec": {
                    "restartPolicy": "Never",
                    "serviceAccountName": "fitzza-pipeline",
                    "nodeSelector": {"kubernetes.io/arch": "arm64", "fitzza.io/pipeline-worker": "true"},
                    "containers": [{
                        "name": stage.replace("-", "-"),
                        "image": image,
                        "command": command,
                        "env": env,
                        "resources": {
                            "requests": {"cpu": cpu_request, "memory": memory_request},
                            "limits": {"cpu": cpu_limit, "memory": memory_limit},
                        },
                    }],
                },
            },
        },
    }
