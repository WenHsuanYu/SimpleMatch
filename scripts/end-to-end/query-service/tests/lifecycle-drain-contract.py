#!/usr/bin/env python3
"""Verify drain behavior with actual OS processes and a simulated Kubernetes/CRI boundary."""

import importlib.util
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
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from lib.security_lab import Lab

SPEC = importlib.util.spec_from_file_location("lifecycle", ROOT / "security-lifecycle.py")
LIFECYCLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LIFECYCLE)


class LifecycleDrainContract(unittest.TestCase):
    def test_already_completed_requests_do_not_require_an_artificial_live_generation(self):
        self.exercise(exited=True)

    def test_remaining_owned_process_is_forced_to_stop_within_the_budget(self):
        self.exercise()

    def test_a_process_that_does_not_stop_cannot_pass_the_deadline(self):
        self.exercise(ignore_kill=True)

    def exercise(self, exited=False, ignore_kill=False):
        actual_run = subprocess.run
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        uid, container = str(uuid.uuid4()), uuid.uuid4().hex
        start = Path(f"/proc/{child.pid}/stat").read_text().split()[21]
        task = {"node": "worker", "container": container, "uid": uid, "pid": child.pid, "start": start}
        signals = []

        def external(arguments, **options):
            if arguments[0] == "kubectl":
                value = {"items": [{"metadata": {"name": "worker"}}]}
            elif arguments[0] == "docker":
                if "inspect" in arguments:
                    value = {"status": {"state": "CONTAINER_RUNNING" if child.poll() is None else "CONTAINER_EXITED",
                        "labels": {"io.kubernetes.pod.uid": uid}}}
                elif "ps" in arguments:
                    value = {"containers": []}
                elif "tasks" in arguments:
                    return subprocess.CompletedProcess(arguments, 0, container if child.poll() is None else "", "")
                elif "cat" in arguments:
                    return actual_run(["cat", arguments[-1]], **options)
                elif "/bin/kill" in arguments:
                    signals.append(arguments[-1])
                    if ignore_kill:
                        return subprocess.CompletedProcess(arguments, 0, "", "")
                    return actual_run(["/bin/kill", "-KILL", str(child.pid)], **options)
                else:
                    raise AssertionError(arguments)
            else:
                raise AssertionError(arguments)
            return subprocess.CompletedProcess(arguments, 0, json.dumps(value), "")

        try:
            with tempfile.TemporaryDirectory(prefix="query-drain-contract-") as temporary:
                lab = Lab(Path(__file__).resolve().parents[4], Path(temporary) / "run", "lab", "lab", "query:test")
                if exited:
                    child.terminate()
                    child.wait(timeout=2)
                with patch("subprocess.run", side_effect=external):
                    if ignore_kill:
                        with self.assertRaisesRegex(RuntimeError, "drain deadline"):
                            LIFECYCLE.drain(lab, [task], seconds=0.3, force_after=0.05)
                        self.assertIsNone(child.poll())
                    else:
                        result = LIFECYCLE.drain(lab, [task], seconds=1, force_after=0.05)
                        self.assertIsNotNone(child.poll())
                        self.assertEqual(result["forcedContainers"], [] if exited else [container])
                        self.assertEqual(signals, [] if exited else [str(child.pid)])
        finally:
            if child.poll() is None:
                child.kill()
            child.wait(timeout=2)


if __name__ == "__main__":
    unittest.main()
