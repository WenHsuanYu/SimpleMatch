#!/usr/bin/env python3
"""Exercise the private Lab approval CLI; these synthetic facts are not runtime attestations."""

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "policy-authority.py"
PROVISIONER = ROOT / "provision-security-fixtures.py"
IMAGE = "sha256:" + "a" * 64


class PolicyAuthorityContract(unittest.TestCase):
    def call(self, *arguments):
        return subprocess.run([sys.executable, str(TOOL), *map(str, arguments)],
                              capture_output=True, text=True, check=False)

    def initial(self, directory):
        first = directory / "g1"
        subprocess.run([sys.executable, str(PROVISIONER), str(first)],
                       capture_output=True, check=True)
        authority = directory / "authority"
        self.assertEqual(self.call("create", authority, "--run-id", str(uuid.uuid4()),
            "--namespace", "query-security-" + uuid.uuid4().hex[:12], "--namespace-uid",
            str(uuid.uuid4()), "--image", IMAGE, "--bundle", first).returncode, 0)
        return authority, first

    def proof(self, authority, event, facts):
        status = json.loads(self.call("status", authority).stdout)
        return {**facts, **{key: status.get(key) for key in
                ("runId", "namespace", "namespaceUid", "image", "activationId", "verificationId")},
                "event": event, "policyVersion": status["version"], "policyDigest": status["digest"],
                "observedAtEpochNs": time.time_ns(), "observedAtMonotonicNs": time.monotonic_ns()}

    def test_initial_activation_rejects_unbound_or_other_run_evidence(self):
        with tempfile.TemporaryDirectory(prefix="query-policy-proof-") as temporary:
            directory = Path(temporary)
            authority, _ = self.initial(directory)
            proof = directory / "proof.json"
            proof.write_text(json.dumps({"allReplicasVerified": True}))
            proof.chmod(0o600)
            self.assertNotEqual(self.call("activate", authority, "--evidence-file", proof).returncode, 0)
            other = self.proof(authority, "InitialActivated", {"allReplicasVerified": True, "eligibleReplicas": 2})
            other["namespaceUid"] = str(uuid.uuid4())
            proof.write_text(json.dumps(other))
            self.assertNotEqual(self.call("activate", authority, "--evidence-file", proof).returncode, 0)

    def test_changed_client_material_cannot_be_released_after_approval(self):
        with tempfile.TemporaryDirectory(prefix="query-policy-material-") as temporary:
            directory = Path(temporary)
            authority, first = self.initial(directory)
            proof = directory / "proof.json"
            proof.write_text(json.dumps(self.proof(authority, "InitialActivated",
                {"allReplicasVerified": True, "eligibleReplicas": 2})))
            proof.chmod(0o600)
            self.assertEqual(self.call("activate", authority, "--evidence-file", proof).returncode, 0)
            credentials = json.loads((first / "credentials.json").read_text())
            credentials["client-c"]["token"] = credentials["client-b"]["token"]
            (first / "credentials.json").write_text(json.dumps(credentials))
            self.assertNotEqual(self.call("collectors", authority,
                "--output-file", directory / "clients.json").returncode, 0)

    def test_nonregular_material_is_rejected_without_blocking_the_authority(self):
        with tempfile.TemporaryDirectory(prefix="query-policy-fifo-") as temporary:
            directory = Path(temporary)
            authority, first = self.initial(directory)
            (first / "application.properties").unlink()
            os.mkfifo(first / "application.properties", 0o600)
            try:
                result = subprocess.run([sys.executable, str(TOOL), "check", str(authority),
                    "--bundle", str(first), "--image", IMAGE], capture_output=True, timeout=1)
            except subprocess.TimeoutExpired:
                self.fail("Special files must be rejected before reading or holding the approval lock")
            self.assertNotEqual(result.returncode, 0)

    def test_current_approval_rejects_policy_and_image_rollback(self):
        with tempfile.TemporaryDirectory(prefix="query-policy-contract-") as temporary:
            directory = Path(temporary)
            first, second = directory / "g1", directory / "g2"
            for arguments in ([first], [second, "--previous-directory", first,
                                       "--policy-version", "2", "--profile", "c-y-revoked"]):
                self.assertEqual(subprocess.run(
                    [sys.executable, str(PROVISIONER), *map(str, arguments)],
                    capture_output=True).returncode, 0)
            authority = directory / "authority"
            run_id = str(uuid.uuid4())
            result = self.call("create", authority, "--run-id", run_id,
                               "--namespace", "query-security-" + uuid.uuid4().hex[:12],
                               "--namespace-uid", str(uuid.uuid4()), "--image", IMAGE,
                               "--bundle", first)
            self.assertEqual(result.returncode, 0, "Fresh run approval must be supported")
            digest = hashlib.sha256((first / "application.properties").read_bytes()).hexdigest()
            self.assertEqual(self.call("approve", authority, "--bundle", second,
                                       "--expect-current-digest", digest).returncode, 0)
            self.assertEqual(self.call("check", authority, "--bundle", second,
                                       "--image", IMAGE).returncode, 0)
            self.assertNotEqual(self.call("check", authority, "--bundle", first,
                                          "--image", IMAGE).returncode, 0)
            self.assertNotEqual(self.call("check", authority, "--bundle", second,
                                          "--image", "sha256:" + "b" * 64).returncode, 0)
            with (second / "application.properties").open("a") as stream:
                stream.write("# changed same-version content\n")
            self.assertNotEqual(self.call("check", authority, "--bundle", second,
                                          "--image", IMAGE).returncode, 0)

    def test_missing_record_and_runner_restart_cannot_reinitialize_v1(self):
        with tempfile.TemporaryDirectory(prefix="query-policy-lost-") as temporary:
            directory = Path(temporary)
            first = directory / "g1"
            subprocess.run([sys.executable, str(PROVISIONER), str(first)],
                           capture_output=True, check=True)
            authority = directory / "authority"
            create = ("create", authority, "--run-id", str(uuid.uuid4()), "--namespace",
                      "query-security-" + uuid.uuid4().hex[:12], "--namespace-uid",
                      str(uuid.uuid4()), "--image", IMAGE, "--bundle", first)
            self.assertEqual(self.call(*create).returncode, 0)
            (authority / "current.json").unlink()
            self.assertNotEqual(self.call("check", authority, "--bundle", first,
                                          "--image", IMAGE).returncode, 0)
            self.assertNotEqual(self.call(*create).returncode, 0)

    def test_collectors_are_withheld_until_verified_resume(self):
        with tempfile.TemporaryDirectory(prefix="query-policy-resume-") as temporary:
            directory = Path(temporary)
            first, second = directory / "g1", directory / "g2"
            for arguments in ([first], [second, "--previous-directory", first,
                                       "--policy-version", "2", "--profile", "c-y-revoked"]):
                subprocess.run([sys.executable, str(PROVISIONER), *map(str, arguments)],
                               capture_output=True, check=True)
            authority = directory / "authority"
            self.assertEqual(self.call("create", authority, "--run-id", str(uuid.uuid4()),
                "--namespace", "query-security-" + uuid.uuid4().hex[:12], "--namespace-uid",
                str(uuid.uuid4()), "--image", IMAGE, "--bundle", first).returncode, 0)
            digest = hashlib.sha256((first / "application.properties").read_bytes()).hexdigest()
            self.assertEqual(self.call("approve", authority, "--bundle", second,
                                       "--expect-current-digest", digest).returncode, 0)
            output = directory / "collectors.json"
            self.assertNotEqual(self.call("collectors", authority, "--output-file", output).returncode, 0)
            self.assertFalse(output.exists())
            proof = directory / "proof.json"
            facts = {"eligibleReplicas": 2, "newRequestsRejected": True,
                "existingConnectionsRejected": True, "directPathsRejected": True,
                "oldProcessesStopped": True, "allReplicasVerified": True,
                "oldCredentialsRejected": True, "catchupComplete": True,
                "policyVersion": 2,
                "policyDigest": hashlib.sha256((second / "application.properties").read_bytes()).hexdigest()}
            for event in ("Tblock", "Tdrained", "Trevoked"):
                proof.write_text(json.dumps(self.proof(authority, event, facts)))
                proof.chmod(0o600)
                self.assertEqual(self.call("mark", authority, "--event", event,
                                           "--evidence-file", proof).returncode, 0,
                                 "Approved state transition must be supported")
            self.assertEqual(self.call("failure", authority).returncode, 0)
            self.assertNotEqual(self.call("collectors", authority, "--output-file", output).returncode, 0)
            self.assertEqual(self.call("verify", authority).returncode, 0)
            proof.write_text(json.dumps(self.proof(authority, "Tresume", facts)))
            self.assertEqual(self.call("mark", authority, "--event", "Tresume",
                                       "--evidence-file", proof).returncode, 0)
            self.assertEqual(self.call("collectors", authority, "--output-file", output).returncode, 0)
            self.assertEqual(json.loads(output.read_text()),
                             json.loads((second / "credentials.json").read_text()))
            status = json.loads(self.call("status", authority).stdout)
            self.assertEqual(status["phase"], "RESUMED")


if __name__ == "__main__":
    unittest.main()
