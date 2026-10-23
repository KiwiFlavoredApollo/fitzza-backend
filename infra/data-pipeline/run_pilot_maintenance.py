"""Run the fixed pilot with a bounded two-service maintenance window."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import time

APPS = ("recommendation-service", "vton-service")
JOB = "crawler-pilot-20261021-01"
PIPELINE = "fitzza-prod-delivery"
CONTEXT = "arn:aws:eks:ap-northeast-2:414901227428:cluster/fitzza-prod"


def command(args, *, timeout=60, structured=False):
    result = subprocess.run(args, check=True, capture_output=True, timeout=timeout)
    try:
        output = result.stdout.decode("utf-8")
    except UnicodeDecodeError:
        output = result.stdout.decode("cp949")
    return json.loads(output) if structured else output.strip()


def kubectl(*args, **kwargs):
    return command(["kubectl", "--context", CONTEXT, "-n", "fitzza", *args], **kwargs)


def aws(*args, **kwargs):
    return command(["aws", *args, "--profile", "fitzza", "--region", "ap-northeast-2"], **kwargs)


def deploy_state():
    state = aws("codepipeline", "get-pipeline-state", "--name", PIPELINE, structured=True)
    return next(stage for stage in state["stageStates"] if stage["stageName"] == "Deploy")


def restore(state):
    for app, replicas in state["replicas"].items():
        kubectl("scale", f"deployment/{app}", f"--replicas={replicas}")
    for app, replicas in state["replicas"].items():
        if replicas:
            kubectl("rollout", "status", f"deployment/{app}", "--timeout=300s", timeout=330)
    if state["deploy_transition_enabled"]:
        aws("codepipeline", "enable-stage-transition", "--pipeline-name", PIPELINE,
            "--stage-name", "Deploy", "--transition-type", "Inbound")
    print("Original application replicas and Deploy transition restored", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True, type=Path)
    parser.add_argument("--recover", action="store_true")
    parser.add_argument("--execute-approved-run", action="store_true",
                        help="Only pass after separate human authorization to execute collection")
    args = parser.parse_args()
    if args.recover:
        restore(json.loads(args.state.read_text(encoding="utf-8")))
        return
    if not args.execute_approved_run:
        raise ValueError("Deployment does not authorize collection; explicit execution approval is required")
    now = datetime.now(timezone.utc)
    if now.date().isoformat() != "2026-10-21" or not 0 <= now.hour < 8:
        raise ValueError("This pilot requires start before 17:00 KST on 2026-10-21")
    stage = deploy_state()
    if stage.get("latestExecution", {}).get("status") == "InProgress":
        raise ValueError("An active Deploy prevents maintenance")
    hpas = kubectl("get", "hpa", "-o", "json", structured=True)
    if any(hpa["spec"]["scaleTargetRef"]["name"] in APPS for hpa in hpas["items"]):
        raise ValueError("Autoscaling targets require a separate maintenance plan")
    job = kubectl("get", "job", JOB, "-o", "json", structured=True)
    if not job["spec"].get("suspend") or job.get("status", {}).get("startTime"):
        raise ValueError("Pilot must be a fresh suspended Job")
    state = {"replicas": {}, "deploy_transition_enabled": stage["inboundTransitionState"]["enabled"]}
    for app in APPS:
        deployment = kubectl("get", "deployment", app, "-o", "json", structured=True)
        state["replicas"][app] = deployment["spec"].get("replicas", 1)
    with args.state.open("x", encoding="utf-8") as file:
        json.dump(state, file, indent=2)
    try:
        aws("codepipeline", "disable-stage-transition", "--pipeline-name", PIPELINE,
            "--stage-name", "Deploy", "--transition-type", "Inbound",
            "--reason", "Bounded cloud crawler pilot; restore after two-service maintenance")
        if deploy_state().get("latestExecution", {}).get("status") == "InProgress":
            raise ValueError("Deploy started before transition was disabled")
        for app in APPS:
            kubectl("scale", f"deployment/{app}", "--replicas=0")
        print("Two AI services paused; Qdrant, Redis and other apps unchanged", flush=True)
        kubectl("patch", "job", JOB, "--type=merge", "-p", json.dumps({"spec": {"suspend": False}}))
        deadline = time.monotonic() + 1260
        while time.monotonic() < deadline:
            job = kubectl("get", "job", JOB, "-o", "json", structured=True)
            conditions = job.get("status", {}).get("conditions", [])
            if any(item["type"] == "Failed" and item["status"] == "True" for item in conditions):
                raise RuntimeError("Pilot Job failed; inspect logs and S3 checkpoints")
            if any(item["type"] == "Complete" and item["status"] == "True" for item in conditions):
                print("Pilot Job completed; data is NOT approved for indexing", flush=True)
                return
            time.sleep(10)
        raise TimeoutError("Pilot exceeded maintenance limit")
    finally:
        # Stopping this Job must never prevent restoring the existing services.
        try:
            kubectl("patch", "job", JOB, "--type=merge", "-p", json.dumps({"spec": {"suspend": True}}))
        finally:
            restore(state)


if __name__ == "__main__":
    main()
