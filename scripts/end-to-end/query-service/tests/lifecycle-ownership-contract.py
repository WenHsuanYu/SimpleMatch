#!/usr/bin/env python3
"""Validate namespace ownership through public Lab APIs and a simulated command boundary."""

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


class LifecycleOwnershipContract(unittest.TestCase):
    def test_create_response_establishes_ownership_without_a_second_uid_lookup(self):
        self.exercise("read-fails")

    def test_ambiguous_create_recovers_only_owned_uid_for_cleanup_not_v1_activation(self):
        self.exercise("ambiguous")

    def test_ambiguous_create_never_adopts_or_deletes_a_foreign_namespace(self):
        self.exercise("foreign")

    def exercise(self, mode):
        original_run = subprocess.run
        uid = str(uuid.uuid4())
        deleted = []
        prior_umask = os.umask(0o077)

        def command(arguments, **options):
            if arguments[0] not in ("kubectl", "docker"):
                return original_run(arguments, **options)
            namespace = {"metadata": {"name": lab.namespace, "uid": uid,
                         "labels": {LABEL: lab.run_id if mode != "foreign" else "other-run"}}}
            if arguments[0] == "docker":
                value = [{"Id": "sha256:" + "a" * 64}]
            elif "create" in arguments:
                if mode != "read-fails":
                    raise subprocess.TimeoutExpired(arguments, 15)
                value = namespace
            elif "delete" in arguments:
                deleted.append(True)
                value = {}
            elif "namespaces" in arguments or "pods" in arguments or "nodes" in arguments:
                value = {"items": []}
            elif "namespace" in arguments:
                if mode == "read-fails":
                    raise subprocess.TimeoutExpired(arguments, 15)
                if deleted:
                    return subprocess.CompletedProcess(arguments, 0, "", "")
                value = namespace
            elif "events" in arguments:
                value = {"items": []}
            else:
                raise AssertionError("Unexpected external command")
            return subprocess.CompletedProcess(arguments, 0, json.dumps(value), "")

        try:
            with tempfile.TemporaryDirectory(prefix="query-owner-contract-") as temporary:
                lab = Lab(Path(__file__).resolve().parents[4], Path(temporary) / "run", "kind-lab", "lab", "query:test")
                with patch("subprocess.run", side_effect=command):
                    if mode == "read-fails":
                        lab.create()
                        self.assertEqual(lab.uid, uid)
                        self.assertEqual(json.loads((lab.root / "owner.json").read_text())["namespaceUid"], uid)
                    else:
                        with self.assertRaises(subprocess.TimeoutExpired):
                            lab.create()
                        if mode == "ambiguous":
                            self.assertEqual(lab.uid, uid)
                            self.assertFalse(lab.authority.exists(), "Ambiguous create cannot activate another V1")
                            lab.destroy()
                            self.assertTrue(deleted)
                        else:
                            self.assertIsNone(lab.uid)
                            lab.destroy()
                            self.assertFalse(deleted)
        finally:
            os.umask(prior_umask)


if __name__ == "__main__":
    unittest.main()
