#!/usr/bin/env python3
"""Exercise the public Lab lifecycle against a stale external Pod/CRI observation.

Only the Kubernetes/Docker command boundary is simulated. These facts are not runtime evidence.
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lib.security_lab import LABEL, Lab


class LifecycleCleanupContract(unittest.TestCase):
    def setUp(self):
        self.prior_umask = os.umask(0o077)

    def tearDown(self):
        os.umask(self.prior_umask)

    def test_container_exit_during_capture_does_not_prevent_owned_namespace_deletion(self):
        self.assert_owned_cleanup({"startedAt": "2026-10-24T00:00:00Z"})

    def test_running_state_without_a_timestamp_is_still_captured(self):
        self.assert_owned_cleanup({})

    def test_positive_cri_exit_with_zero_pid_is_not_an_observation_failure(self):
        self.assert_owned_cleanup({}, exited=True)

    def assert_owned_cleanup(self, running_state, exited=False):
        actual_run = subprocess.run
        namespace_uid, pod_uid = str(uuid.uuid4()), str(uuid.uuid4())
        state = {"created": False, "deleted": False}

        def command(arguments, **options):
            if arguments[0] not in ("kubectl", "docker"):
                return actual_run(arguments, **options)
            code, value, error = 0, {}, ""
            if arguments[0] == "kubectl":
                if "create" in arguments:
                    state["created"] = True
                    value = {"metadata": {"name": lab.namespace, "uid": namespace_uid, "labels": {LABEL: lab.run_id}}}
                elif "namespaces" in arguments:
                    value = {"items": []}
                elif "namespace" in arguments and "delete" in arguments:
                    state["deleted"] = True
                elif "namespace" in arguments:
                    if state["deleted"]:
                        return subprocess.CompletedProcess(arguments, 0, "", "")
                    value = {"metadata": {"name": lab.namespace, "uid": namespace_uid, "labels": {LABEL: lab.run_id}}}
                elif "pods" in arguments:
                    value = {"items": [{"metadata": {"name": "query-old", "uid": pod_uid},
                        "spec": {"nodeName": "worker"}, "status": {"podIP": "10.0.0.1",
                        "containerStatuses": [{"name": "query-service", "restartCount": 0,
                            "containerID": "containerd://old", "state": {"running": running_state}}]}}]}
                elif "nodes" in arguments:
                    value = {"items": [{"metadata": {"name": "worker"}}]}
            elif "image" in arguments:
                value = [{"Id": "sha256:" + "a" * 64}]
            elif "inspect" in arguments:
                value = {"status": {"labels": {"io.kubernetes.pod.uid": pod_uid},
                                    "state": "CONTAINER_EXITED" if exited else "CONTAINER_RUNNING"},
                         "info": {"pid": 0 if exited else 1234}}
            elif "cat" in arguments:
                code, error = 1, "cat: /proc/0/stat: No such file or directory"
            elif "ps" in arguments:
                value = {"containers": []}
            return subprocess.CompletedProcess(arguments, code, json.dumps(value), error)

        with tempfile.TemporaryDirectory(prefix="query-cleanup-contract-") as temporary:
            lab = Lab(Path(__file__).resolve().parents[4], Path(temporary) / "run", "kind-lab", "lab", "query:test")
            with patch("subprocess.run", side_effect=command):
                lab.create()
                if exited:
                    lab.destroy()
                else:
                    with self.assertRaisesRegex(RuntimeError, "capture"):
                        lab.destroy()
            self.assertTrue(state["deleted"], "A stale running status must not prevent exact-owned teardown")
            result = json.loads((lab.root / "teardown.json").read_text())
            self.assertTrue(result["namespaceGone"])
            self.assertEqual(result["captureFailures"], 0 if exited else 1)


if __name__ == "__main__":
    unittest.main()
