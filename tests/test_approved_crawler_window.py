from datetime import datetime, timedelta, timezone
import json
import os
import types
import unittest
from unittest.mock import Mock, patch

from scripts.configure_approved_crawler_window import main, schedule_specs, template_code

KST = timezone(timedelta(hours=9))


class ApprovedWindowTests(unittest.TestCase):
    def runtime(self, moment):
        client = Mock()
        client.update_nodegroup_config.return_value = {"update": {"id": "test", "status": "InProgress"}}
        boto = types.SimpleNamespace(client=Mock(return_value=client))
        runtime = {}
        with patch.dict("sys.modules", {"boto3": boto}):
            exec(compile(template_code(), "node_scaler.py", "exec"), runtime)
        runtime["datetime"] = Mock(now=Mock(return_value=datetime.fromisoformat(moment).replace(tzinfo=KST)))
        return runtime, client

    def call(self, moment, event):
        runtime, client = self.runtime(moment)
        with patch.dict(os.environ, {"BLOCKED_SCALE_UP_DATES": "2026-10-19,2026-10-26",
                                    "CLUSTER_NAME": "test", "NODE_GROUP_NAME": "test"}):
            result = runtime["handler"](event, None)
        return result, client

    def test_exception_start_boundary_all_three_days(self):
        for day in (24, 25, 26):
            for time, permitted in (("11:59:59", False), ("12:00:00", True),
                                    ("18:00:00", True), ("22:59:59", True), ("23:00:00", False)):
                with self.subTest(day=day, time=time):
                    result, client = self.call(f"2026-10-{day}T{time}", {"desiredSize": 1})
                    self.assertEqual(client.update_nodegroup_config.called, permitted)
                    if not permitted:
                        self.assertEqual(result["status"], "SKIPPED")

    def test_monday_regular_09_start_skipped(self):
        _, client = self.call("2026-10-26T09:00:00", {"desiredSize": 1})
        client.update_nodegroup_config.assert_not_called()

    def test_regular_18_stop_skipped_only_during_exception(self):
        result, client = self.call("2026-10-26T18:00:00",
                                  {"desiredSize": 0, "scheduledWeekdayShutdown": True})
        self.assertEqual(result["status"], "SKIPPED")
        client.update_nodegroup_config.assert_not_called()
        _, client = self.call("2026-10-27T18:00:00",
                              {"desiredSize": 0, "scheduledWeekdayShutdown": True})
        client.update_nodegroup_config.assert_called_once()

    def test_23_stop_and_manual_emergency_stop_allowed(self):
        for hour in (18, 23):
            _, client = self.call(f"2026-10-24T{hour}:00:00", {"desiredSize": 0})
            client.update_nodegroup_config.assert_called_once()
            self.assertEqual(client.update_nodegroup_config.call_args.kwargs["scalingConfig"],
                             {"minSize": 0, "maxSize": 1, "desiredSize": 0})

    def test_regular_exclusions_and_weekdays_preserved(self):
        for date, allowed in (("2026-10-19", False), ("2026-10-23", True),
                              ("2026-10-27", True), ("2026-10-31", False)):
            _, client = self.call(date + "T12:00:00", {"desiredSize": 1})
            self.assertEqual(client.update_nodegroup_config.called, allowed)

    def test_invalid_capacity_rejected(self):
        for value in (True, -1, 2, "1"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.call("2026-10-24T12:00:00", {"desiredSize": value})

    def test_six_bounded_schedules_preserve_role_and_target(self):
        target = {"Arn": "lambda-arn", "RoleArn": "existing-role", "Input": "old"}
        specs = schedule_specs(target)
        self.assertEqual(len(specs), 6)
        self.assertEqual(len({s["Name"] for s in specs}), 6)
        expected_sizes = {
            "at(2026-10-24T12:00:00)": 1,
            "at(2026-10-24T23:00:00)": 0,
            "at(2026-10-25T12:00:00)": 1,
            "at(2026-10-25T23:00:00)": 0,
            "at(2026-10-26T12:00:00)": 1,
            "at(2026-10-26T23:00:00)": 0,
        }
        self.assertEqual({spec["ScheduleExpression"] for spec in specs}, set(expected_sizes))
        for spec in specs:
            self.assertEqual(spec["ScheduleExpressionTimezone"], "Asia/Seoul")
            self.assertEqual(spec["ActionAfterCompletion"], "DELETE")
            self.assertEqual(spec["Target"]["Arn"], "lambda-arn")
            self.assertEqual(spec["Target"]["RoleArn"], "existing-role")
            self.assertEqual(json.loads(spec["Target"]["Input"])["desiredSize"],
                             expected_sizes[spec["ScheduleExpression"]])
            self.assertEqual(spec["Target"]["RetryPolicy"],
                             {"MaximumEventAgeInSeconds": 300, "MaximumRetryAttempts": 2})
        self.assertEqual(target["Input"], "old")

    def test_default_makes_no_aws_calls(self):
        with patch("sys.argv", ["configure_window"]), patch("boto3.Session") as session:
            main()
            session.assert_not_called()


if __name__ == "__main__":
    unittest.main()
