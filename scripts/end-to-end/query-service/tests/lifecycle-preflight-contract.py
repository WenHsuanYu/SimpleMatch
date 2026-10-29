#!/usr/bin/env python3
"""Validate Lab prerequisites at the external process boundary, without deploying resources."""

import importlib.util
import json
import subprocess
import sys
from types import SimpleNamespace
import unittest
from pathlib import Path
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("lifecycle", ROOT / "security-lifecycle.py")
LIFECYCLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LIFECYCLE)


class LifecyclePreflightContract(unittest.TestCase):
    def setUp(self):
        # Process-boundary fixtures must not depend on the host's free disk space.
        disk = patch("shutil.disk_usage", return_value=SimpleNamespace(free=80 * 1024**3))
        disk.start()
        self.addCleanup(disk.stop)

    def external(self, arguments, **options):
        self.assertGreater(options.get("timeout", 0), 0, str(arguments))
        self.assertLessEqual(options["timeout"], 30)
        if arguments[0] == "kubectl" and "kustomize" not in arguments:
            self.assertIn("--request-timeout=15s", arguments)
        if "component in (etcd,kube-apiserver,kube-controller-manager,kube-scheduler)" in arguments:
            value = {"items": [{"metadata": {"uid": str(i)}, "status": {
                "containerStatuses": [{"ready": True, "restartCount": 0}]}} for i in range(4)]}
        elif "nodes" in arguments:
            value = {"items": [{"metadata": {"name": "worker-" + str(i), "uid": str(i),
                "labels": {"simplematch.io/node-pool": "local-resilience",
                           "simplematch.io/worker-slot": str(i)}},
                "status": {"conditions": [{"type": "Ready", "status": "True"}]}} for i in range(3)]}
        elif "namespaces" in arguments:
            value = {"items": []}
        elif "image" in arguments:
            value = [{"Id": "sha256:" + "b" * 64}]
        elif "inspect" in arguments:
            value = [{"Config": {"Labels": {"io.x-k8s.kind.cluster": "lab"}}, "Id": "node-id"}]
        elif "crictl" in arguments:
            value = {"containers": []}
        elif "kustomize" in arguments:
            return "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: query-service\n"
        elif arguments[0] == "ruby":
            value = [{"kind": "Deployment", "metadata": {"name": "query-service"}, "spec": {"template": {
                "metadata": {"labels": {}}, "spec": {"containers": [{"name": "query-service",
                    "volumeMounts": [{"name": "artifact", "mountPath": "/etc/simplematch/market-reference"}]}],
                    "volumes": [{"name": "artifact", "configMap": {"name": "matching-daily-artifact"}}]}}}},
                *[{"kind": "StatefulSet", "metadata": {"name": name}, "spec": {"template": {"spec": {}}}}
                  for name in ("postgres", "kafka")]]
        else:
            return "ready"
        return json.dumps(value)

    def test_all_preflight_commands_have_process_and_kubernetes_request_deadlines(self):
        with patch("subprocess.check_output", side_effect=self.external), \
                patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0)), \
                patch("time.sleep"):
            result = LIFECYCLE.preflight("kind-lab", "lab")
        self.assertEqual(result["rejectedImage"]["id"], "sha256:" + "b" * 64)

    def test_missing_rejection_candidate_is_rejected_before_deployment(self):
        calls = []

        def missing(arguments, **options):
            calls.append(arguments)
            if "image" in arguments:
                raise subprocess.CalledProcessError(1, arguments)
            return self.external(arguments, **options)

        with patch("subprocess.check_output", side_effect=missing), \
                patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0)), \
                patch("time.sleep"):
            with self.assertRaises(subprocess.CalledProcessError):
                LIFECYCLE.preflight("kind-lab", "lab")
        self.assertFalse(any("apply" in c or "create" in c for c in calls))

    def test_low_disk_space_blocks_deployment(self):
        with patch("subprocess.check_output", side_effect=self.external), \
                patch("shutil.disk_usage", return_value=SimpleNamespace(free=39 * 1024**3)), \
                patch("subprocess.run") as run:
            with self.assertRaisesRegex(RuntimeError, "40 GiB"):
                LIFECYCLE.preflight("kind-lab", "lab")
        run.assert_not_called()

    def test_raw_probe_survivors_and_duplicate_worker_slots_block_a_new_run(self):
        for failure in ("tasks", "containers", "snapshots", "slots"):
            def changed(arguments, **options):
                if "ctr" in arguments and failure in arguments:
                    return "qs193-interrupted"
                value = self.external(arguments, **options)
                if "nodes" in arguments and failure == "slots":
                    nodes = json.loads(value)
                    nodes["items"][2]["metadata"]["labels"]["simplematch.io/worker-slot"] = "1"
                    return json.dumps(nodes)
                return value

            with self.subTest(failure=failure), patch("subprocess.check_output", side_effect=changed), \
                    patch("subprocess.run", return_value=subprocess.CompletedProcess([], 0)), \
                    patch("time.sleep"), self.assertRaisesRegex(RuntimeError, "probe|slots"):
                LIFECYCLE.preflight("kind-lab", "lab")


if __name__ == "__main__":
    unittest.main()
