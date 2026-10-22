"""Install only the approved 2026-10-24..26 node schedules; never invoke targets."""

import argparse
import ast
from copy import deepcopy
import io
import json
from pathlib import Path
import urllib.request
import zipfile

import boto3
from botocore.config import Config

FUNCTION = "fitzza-prod-node-scaler"
TEMPLATE = Path(__file__).resolve().parents[1] / "infra/aws/cloudformation/foundation.yaml"


def template_code() -> str:
    text = TEMPLATE.read_text(encoding="utf-8")
    block = text.split("  NodeScalerFunction:", 1)[1].split("        ZipFile: |\n", 1)[1]
    block = block.split("      Environment:", 1)[0]
    return "\n".join(line[10:] if line.startswith("          ") else line for line in block.splitlines()) + "\n"


def schedule_specs(target: dict) -> list[dict]:
    schedules = []
    for day in (24, 25, 26):
        for hour, desired in ((12, 1), (23, 0)):
            scoped_target = deepcopy(target)
            scoped_target["Input"] = json.dumps({"desiredSize": desired}, separators=(",", ":"))
            # Do not replay starts into the next day; Lambda also checks wall-clock time.
            scoped_target["RetryPolicy"] = {"MaximumEventAgeInSeconds": 300, "MaximumRetryAttempts": 2}
            schedules.append({
                "Name": f"fitzza-prod-crawler-202610{day}-{'up' if desired else 'down'}",
                "GroupName": "default", "ScheduleExpression": f"at(2026-10-{day}T{hour}:00:00)",
                "ScheduleExpressionTimezone": "Asia/Seoul", "FlexibleTimeWindow": {"Mode": "OFF"},
                "State": "ENABLED", "ActionAfterCompletion": "DELETE", "Target": scoped_target,
                "Description": "Approved crawler operating window only; does not start data Jobs",
            })
    return schedules


def install(session, backup: Path) -> None:
    config = Config(connect_timeout=5, read_timeout=30,
                    retries={"mode": "standard", "total_max_attempts": 3})
    scheduler = session.client("scheduler", config=config)
    function = session.client("lambda", config=config)
    identity = session.client("sts", config=config).get_caller_identity()
    if identity["Account"] != "414901227428":
        raise ValueError("Wrong AWS account")
    details = function.get_function(FunctionName=FUNCTION)
    if details["Configuration"]["State"] != "Active":
        raise ValueError("Lambda is not active")
    with urllib.request.urlopen(details["Code"]["Location"], timeout=30) as response:
        original_zip = response.read(5 * 1024 * 1024 + 1)
    if len(original_zip) > 5 * 1024 * 1024:
        raise ValueError("Unexpected Lambda package size")
    with zipfile.ZipFile(io.BytesIO(original_zip)) as package:
        if set(package.namelist()) != {"index.py"}:
            raise ValueError("Unexpected Lambda package files; refusing replacement")
        original_code = package.read("index.py").decode("utf-8")
    proposed = template_code()
    # Only the reviewed exception/weekday-shutdown guard may differ from live code.
    baseline = proposed.replace(
        '    if "2026-10-24" <= now.date().isoformat() <= "2026-10-26":\n'
        '        return not 12 <= now.hour < 23\n', "")
    start = baseline.index("    exception_shutdown = (")
    end = baseline.index('        return {', start)
    baseline = baseline[:start] + "    if desired_size == 1 and is_scale_up_blocked(now):\n" + baseline[end:]
    if ast.dump(ast.parse(original_code)) != ast.dump(ast.parse(baseline)):
        raise ValueError("Live Lambda differs from reviewed baseline; refusing overwrite")
    down = scheduler.get_schedule(Name="fitzza-prod-nodes-down", GroupName="default")
    up = scheduler.get_schedule(Name="fitzza-prod-nodes-up", GroupName="default")
    if up["Target"]["Arn"] != details["Configuration"]["FunctionArn"]:
        raise ValueError("Unexpected scheduler target")
    specs = schedule_specs(up["Target"])
    for spec in specs:
        try:
            scheduler.get_schedule(Name=spec["Name"], GroupName="default")
        except scheduler.exceptions.ResourceNotFoundException:
            continue
        raise ValueError("Exception schedule already exists; inspect before changing")
    backup.mkdir(parents=True, exist_ok=False)
    (backup / "node-scaler-before.zip").write_bytes(original_zip)
    (backup / "schedules-before.json").write_text(json.dumps({"up": up, "down": down},
        default=str, indent=2), encoding="utf-8")
    payload = io.BytesIO()
    with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED) as package:
        package.writestr("index.py", proposed)
    result = function.update_function_code(FunctionName=FUNCTION, ZipFile=payload.getvalue(),
        RevisionId=details["Configuration"]["RevisionId"])
    function.get_waiter("function_updated_v2").wait(FunctionName=FUNCTION,
        WaiterConfig={"Delay": 2, "MaxAttempts": 25})
    current = function.get_function_configuration(FunctionName=FUNCTION)
    if current["CodeSha256"] != result["CodeSha256"]:
        raise ValueError("Lambda changed concurrently")
    # Create shutdowns first so a partial schedule creation cannot leave a start without a stop.
    for spec in sorted(specs, key=lambda value: json.loads(value["Target"]["Input"])["desiredSize"]):
        scheduler.create_schedule(**spec)
    keys = set(scheduler.meta.service_model.operation_model("UpdateSchedule").input_shape.members)
    request = {key: value for key, value in down.items() if key in keys}
    request["Target"] = deepcopy(down["Target"])
    request["Target"]["Input"] = '{"desiredSize":0,"scheduledWeekdayShutdown":true}'
    scheduler.update_schedule(**request)
    observed_down = scheduler.get_schedule(Name=down["Name"], GroupName=down["GroupName"])
    if any(observed_down.get(key) != value for key, value in request.items()):
        raise ValueError("Weekday shutdown read-back mismatch")
    for spec in specs:
        observed = scheduler.get_schedule(Name=spec["Name"], GroupName="default")
        if any(observed.get(key) != value for key, value in spec.items()):
            raise ValueError("Schedule read-back mismatch")
    print(json.dumps({"schedules": [s["Name"] for s in specs], "timezone": "Asia/Seoul",
        "lambda_sha256": current["CodeSha256"], "targets_invoked": False,
        "backup": str(backup)}, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply-approved-window", action="store_true")
    parser.add_argument("--backup", type=Path)
    args = parser.parse_args()
    if not args.apply_approved_window:
        print("Read-only preparation; no AWS calls. Approved window: 2026-10-24..26 12:00-23:00 KST")
        return
    if not args.backup:
        raise ValueError("A new backup directory is required")
    install(boto3.Session(profile_name="fitzza", region_name="ap-northeast-2"), args.backup)


if __name__ == "__main__":
    main()
