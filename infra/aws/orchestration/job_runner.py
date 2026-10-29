"""Idempotent Kubernetes Job runner used by the pipeline adapter."""

from __future__ import annotations

import time
from typing import Any, Callable

from .job_spec import render_job


class JobExecutionError(RuntimeError):
    """Raised when a Kubernetes Job fails or exceeds its deadline."""


def _status(job: Any) -> str:
    value = getattr(job, "status", None) or {}
    if isinstance(value, dict):
        succeeded, failed, active = value.get("succeeded"), value.get("failed"), value.get("active")
    else:
        succeeded, failed, active = getattr(value, "succeeded", None), getattr(value, "failed", None), getattr(value, "active", None)
    if succeeded:
        return "SUCCEEDED"
    if failed:
        return "FAILED"
    if active:
        return "RUNNING"
    return "PENDING"


def run_job(
    api: Any,
    stage: str,
    contract: dict[str, Any],
    image: str,
    command: list[str],
    namespace: str = "fitzza",
    timeout_seconds: int = 21600,
    poll_seconds: float = 5,
    sleep: Callable[[float], None] = time.sleep,
) -> dict[str, Any]:
    """Create or observe one deterministic Job until it reaches a terminal state."""
    body = render_job(stage, contract, image, command)
    name = body["metadata"]["name"]
    try:
        existing = api.read_namespaced_job(name=name, namespace=namespace)
    except Exception as error:
        if getattr(error, "status", None) != 404:
            raise JobExecutionError(f"could not inspect {name}") from error
        api.create_namespaced_job(namespace=namespace, body=body)
        existing = api.read_namespaced_job(name=name, namespace=namespace)

    started = time.monotonic()
    while True:
        state = _status(existing)
        if state == "SUCCEEDED":
            return {**contract, "stage": stage, "job_name": name, "job_status": state}
        if state == "FAILED":
            raise JobExecutionError(f"pipeline Job failed: {name}")
        if time.monotonic() - started >= timeout_seconds:
            raise JobExecutionError(f"pipeline Job timed out: {name}")
        sleep(poll_seconds)
        existing = api.read_namespaced_job(name=name, namespace=namespace)
