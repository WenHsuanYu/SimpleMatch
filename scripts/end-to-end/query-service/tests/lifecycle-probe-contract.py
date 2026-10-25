#!/usr/bin/env python3
"""Probe-launch timeouts must not strand raw containerd work before namespace creation."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lib.security_lab import LABEL, Lab


class LifecycleProbeContract(unittest.TestCase):
    def test_ambiguous_probe_launch_is_cleaned_without_a_namespace(self):
        self.assert_cleanup()

    def test_orphan_snapshot_is_cleaned_by_exact_owned_identity(self):
        self.assert_cleanup(snapshot=True)

    def test_uncertain_probe_ownership_is_not_deleted_or_reported_absent(self):
        self.assert_cleanup(foreign=True)

    def assert_cleanup(self, snapshot=False, foreign=False):
        state = {"launched": False, "task": False, "container": False, "snapshot": False, "deleted": []}

        def external(arguments, **options):
            self.assertEqual(arguments[0], "docker")
            self.assertLessEqual(options["timeout"], 20)
            value, error, code = "", "", 0
            if "run" in arguments:
                self.assertTrue((lab.root / "image-probes.json").exists())
                self.assertIn(LABEL + "=" + lab.run_id, arguments)
                state.update(launched=True, task=True, container=True, snapshot=snapshot)
                raise subprocess.TimeoutExpired(arguments, options["timeout"])
            if "info" in arguments:
                exists = state["snapshot"] if "snapshots" in arguments else state["container"]
                if not exists:
                    code, error = 1, "ctr: container not found"
                else:
                    value = json.dumps({"ID": arguments[-1], "Labels": {LABEL: "different-run" if foreign else lab.run_id}})
            elif "tasks" in arguments and "ls" in arguments:
                value = lab.probes[0]["id"] if state["task"] else ""
            elif "delete" in arguments:
                state["deleted"].append(arguments[-1])
                state["task" if "tasks" in arguments else "container"] = False
            elif "remove" in arguments:
                state["deleted"].append(arguments[-1])
                state["snapshot"] = False
            return subprocess.CompletedProcess(arguments, code, value, error)

        with tempfile.TemporaryDirectory(prefix="query-probe-contract-") as temporary:
            lab = Lab(Path(__file__).resolve().parents[4], Path(temporary) / "run", "lab", "lab", "query:test")
            with patch("subprocess.run", side_effect=external):
                expected = RuntimeError if foreign else subprocess.TimeoutExpired
                with self.assertRaises(expected):
                    lab.probe("worker", "query:test", ["java", "-version"])
            self.assertIsNone(lab.uid)
            self.assertTrue(state["launched"])
            if foreign:
                self.assertFalse(state["deleted"])
                self.assertFalse(lab.probes[0]["absent"])
                return
            self.assertFalse(state["task"] or state["container"])
            self.assertFalse(state["snapshot"])
            self.assertEqual(state["deleted"], [lab.probes[0]["id"]] * (3 if snapshot else 2))
            self.assertTrue(lab.probes[0]["absent"])


if __name__ == "__main__":
    unittest.main()
