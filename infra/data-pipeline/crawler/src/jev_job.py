"""Explicitly authorized Jev stage; deployment mode never reads the key or calls Jev."""

import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tarfile


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--execute-approved-run", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", args.run_id):
        raise ValueError("Invalid run ID")
    if not args.execute_approved_run:
        print(json.dumps({"mode": "deployment_only", "network_calls": 0, "jev_calls": 0}))
        return
    import boto3
    from botocore.config import Config

    config = Config(connect_timeout=5, read_timeout=30,
                    retries={"mode": "standard", "total_max_attempts": 3})
    s3 = boto3.client("s3", config=config)
    ssm = boto3.client("ssm", config=config)
    prefix = f"pipeline/runs/{args.run_id}"
    archive = Path("/work/curated.tar.gz")
    bundle = Path("/work/review-bundle")
    s3.download_file(args.bucket, f"{prefix}/curated/bundle.tar.gz", str(archive))
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        if len(members) > 30000 or sum(member.size for member in members) > 1024 ** 3:
            raise ValueError("Curated bundle exceeds limits")
        for member in members:
            path = PurePosixPath(member.name)
            if (path.is_absolute() or ".." in path.parts or "\\" in member.name or ":" in member.name
                    or not (member.isfile() or member.isdir())):
                raise ValueError("Unsafe bundle member")
        source.extractall(bundle, members=members, filter="data")
    output = bundle / "config"
    output.mkdir(parents=True, exist_ok=True)
    results = output / "color_map_jev_results.jsonl"
    try:
        response = s3.get_object(Bucket=args.bucket, Key=f"{prefix}/jev/color_map_jev_results.jsonl")
    except s3.exceptions.NoSuchKey:
        pass
    else:
        with response["Body"] as body, results.open("wb") as file:
            shutil.copyfileobj(body, file)
    parameter = ssm.get_parameter(Name="/fitzza/prod/data-pipeline/typesafe-api-key", WithDecryption=True)
    env = dict(os.environ, TYPESAFE_API_KEY=parameter["Parameter"]["Value"])
    try:
        subprocess.run([sys.executable, "/app/jev/scripts/generate_color_map.py",
            "--data", str(bundle / "data"), "--config", str(output), "--workers", "4"],
            env=env, check=True, timeout=1800)
    finally:
        env.pop("TYPESAFE_API_KEY", None)
        parameter.clear()
        if results.exists():
            s3.upload_file(str(results), args.bucket, f"{prefix}/jev/{results.name}")
    shutil.copyfile("/app/jev/scripts/color_map_overrides.json", output / "color_map_overrides.json")
    # No approval marker is generated: local human review must happen after this stage.
    review_archive = Path("/work/review-bundle.tar.gz")
    with tarfile.open(review_archive, "w:gz") as target:
        target.add(bundle / "data", arcname="data")
        target.add(output, arcname="config")
    s3.upload_file(str(review_archive), args.bucket, f"{prefix}/jev/review-bundle.tar.gz")
    print(json.dumps({"status": "waiting_for_human_review", "approved_for_indexing": False}))


if __name__ == "__main__":
    main()
