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
    def test_bundle_inspection_is_read_only_and_rejects_broadened_synthetic_grants(self):
        with tempfile.TemporaryDirectory(prefix="query-policy-inspection-") as temporary:
            directory = Path(temporary) / "bundle"
            subprocess.run([sys.executable, str(PROVISIONER), str(directory)],
                           capture_output=True, check=True)
            result = self.call("inspect-bundle", directory)
            self.assertEqual(result.returncode, 0)
            public = json.loads(result.stdout)
            self.assertEqual(set(public), {"version", "digest", "identitiesDigest", "credentialsDigest"})
            self.assertEqual(public["version"], 1)
            self.assertEqual({p.name for p in directory.iterdir()},
                             {"application.properties", "fixture-identities.json", "credentials.json"})
            credentials = json.loads((directory / "credentials.json").read_text())
            for item in credentials.values():
                self.assertNotIn(item["token"], result.stdout + result.stderr)
                self.assertNotIn(hashlib.sha256(item["token"].encode()).hexdigest(), result.stdout)
            account_y = json.loads((directory / "fixture-identities.json").read_text())["accounts"]["Y"]
            policy = directory / "application.properties"
            policy.write_text(policy.read_text() +
                              "simplematch.query-service.security.principals[0].account-grants[1]=" +
                              account_y + "\n")
            self.assertNotEqual(self.call("inspect-bundle", directory).returncode, 0)
            self.assertFalse((directory / ".lock").exists())
            self.assertFalse((directory / "current.json").exists())

    def call(self, *arguments):
        return subprocess.run([sys.executable, str(TOOL), *map(str, arguments)],
                              capture_output=True, text=True, check=False)

    def initial(self, directory, drain_seconds=20):
        first = directory / "g1"
        subprocess.run([sys.executable, str(PROVISIONER), str(first)],
                       capture_output=True, check=True)
        authority = directory / "authority"
        self.assertEqual(self.call("create", authority, "--run-id", str(uuid.uuid4()),
            "--namespace", "query-security-" + uuid.uuid4().hex[:12], "--namespace-uid",
            str(uuid.uuid4()), "--image", IMAGE, "--bundle", first,
            "--drain-seconds", drain_seconds).returncode, 0)
        return authority, first

    def submitted(self, directory, drain_seconds=20):
        authority, first = self.initial(directory, drain_seconds)
        second = directory / "g2"
        subprocess.run([sys.executable, str(PROVISIONER), str(second), "--previous-directory", str(first),
            "--policy-version", "2", "--profile", "c-y-revoked"], capture_output=True, check=True)
        digest = json.loads(self.call("status", authority).stdout)["digest"]
        self.assertEqual(self.call("approve", authority, "--bundle", second,
                                  "--expect-current-digest", digest).returncode, 0)
        return authority

    def event_file(self, authority, event):
        facts = {"eligibleReplicas": 2, "newRequestsRejected": True,
                 "existingConnectionsRejected": True, "directPathsRejected": True,
                 "oldProcessesStopped": True, "allReplicasVerified": True,
                 "oldCredentialsRejected": True, "catchupComplete": True}
        path = authority.parent / (event + "-" + uuid.uuid4().hex + ".json")
        path.write_text(json.dumps(self.proof(authority, event, facts)))
        path.chmod(0o600)
        return path

    def test_out_of_order_transitions_and_all_pre_resume_collector_exports_are_denied(self):
        with tempfile.TemporaryDirectory(prefix="query-policy-order-") as temporary:
            directory = Path(temporary)
            authority = self.submitted(directory)
            premature = self.event_file(authority, "Trevoked")
            self.assertNotEqual(self.call("mark", authority, "--event", "Trevoked",
                "--evidence-file", premature).returncode, 0)
            for next_event in ("Tblock", "Tdrained", "Trevoked"):
                output = directory / (next_event + "-clients.json")
                self.assertNotEqual(self.call("collectors", authority, "--output-file", output).returncode, 0)
                self.assertFalse(output.exists())
                self.assertEqual(self.call("mark", authority, "--event", next_event,
                    "--evidence-file", self.event_file(authority, next_event)).returncode, 0)
            self.assertNotEqual(self.call("collectors", authority,
                "--output-file", directory / "revoked-clients.json").returncode, 0)
            self.assertEqual(self.call("verify", authority).returncode, 0)
            self.assertNotEqual(self.call("collectors", authority,
                "--output-file", directory / "verifying-clients.json").returncode, 0)
            old_proof = self.event_file(authority, "Tresume")
            self.assertEqual(self.call("failure", authority).returncode, 0)
            self.assertNotEqual(self.call("collectors", authority,
                "--output-file", directory / "failed-clients.json").returncode, 0)
            self.assertEqual(self.call("verify", authority).returncode, 0)
            self.assertNotEqual(self.call("mark", authority, "--event", "Tresume",
                "--evidence-file", old_proof).returncode, 0)
            self.assertEqual(json.loads(self.call("status", authority).stdout)["phase"], "VERIFYING")
            self.assertEqual(self.call("mark", authority, "--event", "Tresume",
                "--evidence-file", self.event_file(authority, "Tresume")).returncode, 0)

    def test_stop_verification_window_cannot_extend_an_expired_drain(self):
        with tempfile.TemporaryDirectory(prefix="query-policy-drain-") as temporary:
            directory = Path(temporary)
            authority = self.submitted(directory, drain_seconds=0.15)
            self.assertEqual(self.call("mark", authority, "--event", "Tblock",
                "--evidence-file", self.event_file(authority, "Tblock")).returncode, 0)
            time.sleep(0.16)
            self.assertNotEqual(self.call("mark", authority, "--event", "Tdrained",
                "--evidence-file", self.event_file(authority, "Tdrained")).returncode, 0)
            self.assertNotEqual(self.call("mark", authority, "--event", "Trevoked",
                "--evidence-file", self.event_file(authority, "Trevoked")).returncode, 0)
            self.assertEqual(json.loads(self.call("status", authority).stdout)["phase"], "BLOCKED")
            self.assertNotEqual(self.call("collectors", authority,
                "--output-file", directory / "late-clients.json").returncode, 0)

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
