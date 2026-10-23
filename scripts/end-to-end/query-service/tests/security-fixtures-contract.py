#!/usr/bin/env python3
"""Exercise the controlled Query fixture provisioner through its public CLI."""

import base64
import hashlib
import json
import re
import stat
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import UUID


PROVISIONER = Path(__file__).resolve().parents[1] / "provision-security-fixtures.py"


class SecurityFixtureContract(unittest.TestCase):
    def test_creates_one_private_complete_fixture_bundle(self):
        with tempfile.TemporaryDirectory(prefix="query-security-contract-") as temporary:
            output = Path(temporary) / "new-run"
            result = subprocess.run(
                [sys.executable, str(PROVISIONER), str(output)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, "Fixture CLI must succeed")
            self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o700)
            self.assertEqual(
                {path.name for path in output.iterdir()},
                {"application.properties", "credentials.json", "fixture-identities.json"},
            )
            for path in output.iterdir():
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            credentials = json.loads((output / "credentials.json").read_text())
            self.assertEqual(
                set(credentials),
                {"client-a", "client-b", "client-c", "operations", "client-d"},
            )

    def test_uses_the_explicit_approved_policy_version(self):
        with tempfile.TemporaryDirectory(prefix="query-security-contract-") as temporary:
            output = Path(temporary) / "version-seven"
            result = subprocess.run(
                [sys.executable, str(PROVISIONER), str(output), "--policy-version", "7"],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, "Explicit approved version must work")
            self.assertTrue(
                "simplematch.query-service.security.policy-version=7\n"
                in (output / "application.properties").read_text(),
                "Policy version must match the approved input",
            )

    def test_matrix_credentials_and_verifiers_are_coherent_without_output_leaks(self):
        with tempfile.TemporaryDirectory(prefix="query-security-contract-") as temporary:
            output = Path(temporary) / "coherent-run"
            started = datetime.now(timezone.utc)
            result = subprocess.run(
                [sys.executable, str(PROVISIONER), str(output)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, "Fixture CLI must succeed")
            finished = datetime.now(timezone.utc)
            credentials = json.loads((output / "credentials.json").read_text())
            identities = json.loads((output / "fixture-identities.json").read_text())
            policy_text = (output / "application.properties").read_text()
            properties = dict(line.split("=", 1) for line in policy_text.splitlines())
            prefix = "simplematch.query-service.security"
            self.assertEqual(properties[f"{prefix}.schema-version"], "1")
            self.assertEqual(set(identities["accounts"]), {"X", "Y"})
            self.assertEqual(set(identities["principals"]), set(credentials))
            all_ids = list(identities["accounts"].values()) + list(identities["principals"].values())
            for identifier in all_ids:
                self.assertEqual(str(UUID(identifier)), identifier)
            self.assertEqual(len(set(all_ids)), 7)
            expected_grants = {
                "client-a": {"X"}, "client-b": {"Y"}, "client-c": {"X", "Y"},
                "operations": set(), "client-d": set(),
            }
            principal_indices = {
                value: re.search(r"principals\[(\d+)\]", key).group(1)
                for key, value in properties.items()
                if re.fullmatch(re.escape(prefix) + r"\.principals\[\d+\]\.id", key)
            }
            credential_indices = {
                value: re.search(r"credentials\[(\d+)\]", key).group(1)
                for key, value in properties.items()
                if re.fullmatch(re.escape(prefix) + r"\.credentials\[\d+\]\.principal-id", key)
            }
            self.assertEqual(set(principal_indices), set(identities["principals"].values()))
            self.assertEqual(set(credential_indices), set(principal_indices))
            tokens = set()
            for alias, credential in credentials.items():
                principal_id = identities["principals"][alias]
                self.assertEqual(credential["principalId"], principal_id)
                principal_prefix = f"{prefix}.principals[{principal_indices[principal_id]}]"
                credential_prefix = f"{prefix}.credentials[{credential_indices[principal_id]}]"
                self.assertEqual(properties[f"{principal_prefix}.status"], "ACTIVE")
                self.assertEqual(
                    properties[f"{principal_prefix}.operations-read"],
                    str(alias == "operations").lower(),
                )
                actual_grants = {
                    value for key, value in properties.items()
                    if key.startswith(principal_prefix + ".account-grants[")
                }
                self.assertEqual(
                    actual_grants, {identities["accounts"][account] for account in expected_grants[alias]}
                )
                token = credential["token"]
                self.assertTrue(re.fullmatch(r"[A-Za-z0-9_-]{43}", token), "Invalid credential shape")
                self.assertEqual(len(base64.urlsafe_b64decode(token + "=")), 32)
                verifier = properties[f"{credential_prefix}.sha256"]
                self.assertTrue(re.fullmatch(r"[0-9a-f]{64}", verifier), "Invalid verifier shape")
                self.assertTrue(
                    verifier == hashlib.sha256(token.encode("ascii")).hexdigest(),
                    "Policy verifier must correspond to the privately distributed credential",
                )
                self.assertEqual(properties[f"{credential_prefix}.id"], credential["credentialId"])
                self.assertEqual(str(UUID(credential["credentialId"])), credential["credentialId"])
                self.assertEqual(properties[f"{credential_prefix}.enabled"], "true")
                self.assertEqual(properties[f"{credential_prefix}.expires-at"], credential["expiresAt"])
                expiry = datetime.fromisoformat(credential["expiresAt"].replace("Z", "+00:00"))
                self.assertTrue(
                    started + timedelta(seconds=3599) <= expiry <= finished + timedelta(hours=1),
                    "Expiry must be one hour from provisioning, allowing timestamp rounding",
                )
                self.assertTrue(token not in policy_text, "Policy must contain verifiers only")
                self.assertTrue(token not in result.stdout + result.stderr, "CLI leaked a credential")
                self.assertTrue(verifier not in result.stdout + result.stderr, "CLI leaked a verifier")
                tokens.add(token)
            self.assertEqual(len(tokens), 5, "Each independently provisioned client needs its own credential")

    def test_existing_directory_file_or_symlink_is_never_overwritten(self):
        with tempfile.TemporaryDirectory(prefix="query-security-contract-") as temporary:
            directory = Path(temporary)
            existing = directory / "existing"
            existing.mkdir()
            marker = existing / "keep.txt"
            marker.write_text("untouched")
            file_target = directory / "file"
            file_target.write_text("untouched")
            link_target = directory / "link"
            link_target.symlink_to(existing, target_is_directory=True)
            for target in (existing, file_target, link_target):
                with self.subTest(target=target.name):
                    result = subprocess.run(
                        [sys.executable, str(PROVISIONER), str(target)],
                        capture_output=True, text=True, check=False,
                    )
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual({path.name for path in existing.iterdir()}, {"keep.txt"})
                    self.assertEqual(marker.read_text(), "untouched")
                    self.assertEqual(file_target.read_text(), "untouched")
            self.assertTrue(link_target.is_symlink())

    def test_invalid_policy_versions_fail_before_creating_output(self):
        with tempfile.TemporaryDirectory(prefix="query-security-contract-") as temporary:
            for index, version in enumerate(("0", "-1", "not-a-version", "9223372036854775808")):
                with self.subTest(version=version):
                    output = Path(temporary) / str(index)
                    result = subprocess.run(
                        [sys.executable, str(PROVISIONER), str(output), "--policy-version", version],
                        capture_output=True, text=True, check=False,
                    )
                    self.assertNotEqual(result.returncode, 0)
                    self.assertFalse(output.exists())

    def test_independent_runs_have_fresh_identities_and_credentials(self):
        with tempfile.TemporaryDirectory(prefix="query-security-contract-") as temporary:
            bundles = []
            for name in ("first", "second"):
                output = Path(temporary) / name
                result = subprocess.run(
                    [sys.executable, str(PROVISIONER), str(output)],
                    capture_output=True, text=True, check=False,
                )
                self.assertEqual(result.returncode, 0, "Independent run must provision")
                bundles.append(json.loads((output / "credentials.json").read_text()))
            for alias in bundles[0]:
                self.assertTrue(
                    bundles[0][alias]["token"] != bundles[1][alias]["token"],
                    "Independent Lab runs must not reuse credentials",
                )
                self.assertNotEqual(bundles[0][alias]["principalId"], bundles[1][alias]["principalId"])


if __name__ == "__main__":
    unittest.main()
