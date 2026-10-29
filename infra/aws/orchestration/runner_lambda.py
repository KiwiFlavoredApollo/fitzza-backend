"""Lambda entrypoint for a Crawler/FashionCLIP Job runner."""

from __future__ import annotations

import json
import os

from .eks_kubernetes_api import EksKubernetesApi
from .job_runner import run_job


def lambda_handler(event, _context):
    stage = os.environ["PIPELINE_STAGE"]
    image = os.environ["PIPELINE_IMAGE"]
    command = json.loads(os.environ["PIPELINE_COMMAND_JSON"])
    api = EksKubernetesApi(os.environ["EKS_CLUSTER_NAME"], os.environ.get("AWS_REGION", "ap-northeast-2"))
    return run_job(
        api,
        stage,
        event,
        image,
        command,
        namespace=os.environ.get("EKS_NAMESPACE", "fitzza"),
        timeout_seconds=int(os.environ.get("PIPELINE_JOB_TIMEOUT_SECONDS", "21600")),
    )
