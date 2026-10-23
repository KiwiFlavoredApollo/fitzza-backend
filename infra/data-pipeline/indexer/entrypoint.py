"""Deployment-safe entrypoint: no network calls without separate execution approval."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile

from review_gate import fingerprint, verify


def unpack_bundle(archive: Path, destination: Path) -> None:
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        if len(members) > 30000 or sum(member.size for member in members) > 1024 ** 3:
            raise ValueError("Bundle exceeds limits")
        for member in members:
            path = PurePosixPath(member.name)
            if (path.is_absolute() or ".." in path.parts or "\\" in member.name or ":" in member.name
                    or not (member.isfile() or member.isdir())):
                raise ValueError("Unsafe bundle member")
        source.extractall(destination, members=members, filter="data")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--execute-approved-run", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", args.run_id):
        raise ValueError("Invalid run ID")
    if not args.execute_approved_run:
        print(json.dumps({"mode": "deployment_only", "network_calls": 0, "indexing": False}))
        return
    import boto3
    from botocore.config import Config

    client = boto3.client("s3", config=Config(connect_timeout=5, read_timeout=30,
        retries={"mode": "standard", "total_max_attempts": 3}))
    prefix = f"pipeline/runs/{args.run_id}"
    archive = Path("/work/bundle.tar.gz")
    bundle = Path("/work/bundle")
    s3_key = f"{prefix}/approved/bundle.tar.gz"
    client.download_file(args.bucket, s3_key, str(archive))
    unpack_bundle(archive, bundle)
    verify(bundle)
    current = fingerprint(bundle)
    quality = json.loads((bundle / "config/_QUALITY_APPROVED.json").read_text(encoding="utf-8"))
    if quality.get("approved") is not True or quality.get("files") != current["files"]:
        raise ValueError("Dataset quality approval is missing or stale")
    if current["product_count"] != 3000:
        raise ValueError("Expected 3000 unique approved products")
    revision = quality.get("model_revision", "")
    if not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise ValueError("Quality manifest must pin model revision")
    env = dict(os.environ, DATA_ROOT=str(bundle / "data"), COLOR_MAP_FILE=str(bundle / "config/color_map.json"),
               MODEL_REVISION=revision, QDRANT_COLLECTION_NAME=f"pipeline_staging_{args.run_id.replace('-', '_')}")
    subprocess.run([sys.executable, "/pipeline/index_to_qdrant.py"], env=env, check=True, timeout=3600)
    report = {"status": "indexed_staging", "products": current["product_count"],
              "bundle_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(), "serving_switched": False}
    client.put_object(Bucket=args.bucket, Key=f"{prefix}/indexer/report.json",
        Body=json.dumps(report).encode("utf-8"), ContentType="application/json")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
