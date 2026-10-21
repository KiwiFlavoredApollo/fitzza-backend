import unittest
import subprocess
from unittest.mock import call, patch

import run_pilot_maintenance as maintenance


class MaintenanceTests(unittest.TestCase):
    def test_windows_cli_json_encoding(self):
        result = subprocess.CompletedProcess([], 0, '{"reason":"검증"}'.encode("cp949"), b"")
        with patch.object(maintenance.subprocess, "run", return_value=result):
            self.assertEqual(maintenance.command(["aws"], structured=True), {"reason": "검증"})

    def test_restore_original_replica_counts_before_enabling_cd(self):
        state = {"replicas": {"recommendation-service": 1, "vton-service": 0},
                 "deploy_transition_enabled": True}
        with patch.object(maintenance, "kubectl") as kube, patch.object(maintenance, "aws") as aws:
            maintenance.restore(state)
            self.assertEqual(kube.call_args_list, [
                call("scale", "deployment/recommendation-service", "--replicas=1"),
                call("scale", "deployment/vton-service", "--replicas=0"),
                call("rollout", "status", "deployment/recommendation-service", "--timeout=300s", timeout=330)])
            aws.assert_called_once()

    def test_failed_recovery_does_not_enable_cd(self):
        state = {"replicas": {"recommendation-service": 1}, "deploy_transition_enabled": True}
        def fail_rollout(*args, **kwargs):
            if args[0] == "rollout":
                raise RuntimeError("not healthy")
        with patch.object(maintenance, "kubectl", side_effect=fail_rollout), patch.object(maintenance, "aws") as aws:
            with self.assertRaises(RuntimeError):
                maintenance.restore(state)
            aws.assert_not_called()

    def test_originally_disabled_cd_remains_disabled(self):
        with patch.object(maintenance, "kubectl"), patch.object(maintenance, "aws") as aws:
            maintenance.restore({"replicas": {}, "deploy_transition_enabled": False})
            aws.assert_not_called()
