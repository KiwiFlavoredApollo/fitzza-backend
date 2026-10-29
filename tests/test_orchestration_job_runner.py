from types import SimpleNamespace

import pytest

from infra.aws.orchestration.job_runner import JobExecutionError, run_job


def contract():
    return {
        "schema_version": 1,
        "run_mode": "incremental",
        "run_id": "collection-20261027-01",
        "input_manifest_sha256": "0" * 64,
        "s3": {"bucket": "fitzza-data", "prefix": "pipeline/runs/collection-20261027-01"},
    }


class Missing(Exception):
    status = 404


class FakeApi:
    def __init__(self, statuses):
        self.statuses = iter(statuses)
        self.created = []

    def read_namespaced_job(self, **kwargs):
        value = next(self.statuses)
        if isinstance(value, Exception):
            raise value
        return SimpleNamespace(status=SimpleNamespace(**value))

    def create_namespaced_job(self, **kwargs):
        self.created.append(kwargs)


def test_runner_creates_then_reuses_job_until_success():
    api = FakeApi([Missing(), {"active": 1}, {"succeeded": 1}])

    result = run_job(api, "crawler", contract(), "fitzza/crawler:reviewed", ["python", "-m", "crawler"], poll_seconds=0, sleep=lambda _: None)

    assert result["job_status"] == "SUCCEEDED"
    assert len(api.created) == 1


def test_runner_surfaces_failed_job():
    api = FakeApi([Missing(), {"failed": 1}])

    with pytest.raises(JobExecutionError, match="failed"):
        run_job(api, "fashion-clip", contract(), "fitzza/fashion-clip:reviewed", ["python", "-m", "index"], poll_seconds=0, sleep=lambda _: None)
