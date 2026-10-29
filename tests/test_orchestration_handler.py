import json

import pytest

from infra.aws.orchestration.handler import ContractError, check_approval, initialize_run, write_status


def contract():
    return {
        "schema_version": 1,
        "run_mode": "full",
        "run_id": "collection-20261027-01",
        "input_manifest_sha256": "0" * 64,
        "s3": {
            "bucket": "fitzza-data",
            "prefix": "pipeline/runs/collection-20261027-01",
        },
    }


class Body:
    def __init__(self, value):
        self.value = value

    def read(self):
        return self.value


class FakeS3:
    class NoSuchKey(Exception):
        pass

    class Exceptions:
        pass

    exceptions = Exceptions()
    exceptions.NoSuchKey = NoSuchKey

    def __init__(self, approval=None, manifest=b"manifest", checkpoint=None):
        self.approval = approval
        self.manifest = manifest
        self.checkpoint = checkpoint
        self.put = None

    def put_object(self, **kwargs):
        self.put = kwargs

    def get_object(self, **kwargs):
        if kwargs["Key"] == "manifest.json":
            return {"Body": Body(self.manifest)}
        if kwargs["Key"] == "pipeline/state/incremental.json":
            if self.checkpoint is None:
                raise self.NoSuchKey()
            return {"Body": Body(json.dumps(self.checkpoint).encode())}
        return {"Body": Body(json.dumps(self.approval).encode())}


def test_write_status_persists_contract_status():
    s3 = FakeS3()
    event = {**contract(), "status": "SUCCEEDED"}

    result = write_status(event, s3)

    assert result["status"] == "SUCCEEDED"
    assert s3.put["Key"].endswith("/status/SUCCEEDED.json")
    assert json.loads(s3.put["Body"])["run_id"] == event["run_id"]


def test_check_approval_rejects_a_different_run():
    event = contract()
    s3 = FakeS3({"approved": True, "run_id": "other", "input_manifest_sha256": "0" * 64})

    assert check_approval(event, s3)["approved"] is False


def test_contract_rejects_a_mismatched_prefix():
    event = {**contract(), "status": "RUNNING", "s3": {"bucket": "fitzza-data", "prefix": "pipeline/runs/other"}}

    with pytest.raises(ContractError):
        write_status(event, FakeS3())


def test_initialize_run_fingerprints_manifest_and_builds_paths():
    result = initialize_run(
        {"run_mode": "incremental", "bucket": "fitzza-data", "manifest_key": "manifest.json"},
        FakeS3(manifest=b'{"products":[{"product_id":"101"}]}'),
    )

    assert result["run_mode"] == "incremental"
    assert result["input_manifest_sha256"]
    assert result["baseline"]["product_count"] == 1
    assert result["checkpoint"]["last_successful_run_id"] is None
    assert result["s3"]["prefix"].startswith("pipeline/runs/collection-")
    assert result["stages"]["jev"]["success_marker"].endswith("/jev/_SUCCESS.json")


def test_initialize_run_rejects_a_full_run_without_manual_approval():
    with pytest.raises(ContractError, match="manual_approved"):
        initialize_run(
            {"run_mode": "full", "bucket": "fitzza-data", "baseline_manifest_key": "manifest.json"},
            FakeS3(manifest=b'{"products":[{"product_id":"101"}]}'),
        )
