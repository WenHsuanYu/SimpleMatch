#!/usr/bin/env python3
"""Exercise private trading certification inputs through the provisioner CLI."""

import json
import os
from pathlib import Path
import socket
import ssl
import stat
import subprocess
import sys
import tempfile
import threading
import unittest


ROOT = Path(__file__).resolve().parents[1]
PROVISIONER = ROOT / "provision-certification-fixtures.py"
CLIENT = ROOT / "lib" / "certification_security.py"


class CertificationFixturesContract(unittest.TestCase):
    def test_rejects_query_policy_not_bound_to_the_approved_fixture_before_launch(self):
        with tempfile.TemporaryDirectory(prefix="query-certification-policy-") as temporary:
            directory = Path(temporary) / "bundle"
            subprocess.run([sys.executable, str(PROVISIONER), str(directory)],
                           capture_output=True, check=True, timeout=60)
            policy = directory / "application.properties"
            original = policy.read_text()
            identities = json.loads((directory / "fixture-identities.json").read_text())
            invalid = (
                "unrelated.empty.policy=true\n",
                original + "simplematch.query-service.security.principals[0].account-grants[1]="
                + identities["accounts"]["Y"] + "\n",
                original.replace("operations-read=false", "operations-read=true", 1),
                original.replace(".enabled=true", ".enabled=false", 1),
            )
            for index, content in enumerate(invalid):
                with self.subTest(case=index):
                    policy.write_text(content)
                    result = subprocess.run([sys.executable, str(CLIENT), "--fixture-directory",
                                             str(directory), "--fingerprint"],
                                            capture_output=True, text=True, timeout=10)
                    self.assertNotEqual(result.returncode, 0, "Reject incoherent or broadened Query policy")
                    self.assertEqual(result.stdout, "")

    def test_rejects_invalid_or_wrong_peer_pkcs12_before_launch(self):
        with tempfile.TemporaryDirectory(prefix="query-certification-stores-") as temporary:
            directory = Path(temporary) / "bundle"
            subprocess.run([sys.executable, str(PROVISIONER), str(directory)],
                           capture_output=True, check=True, timeout=60)
            fix = directory / "fix"
            command = [sys.executable, str(CLIENT), "--fixture-directory", str(directory), "--fingerprint"]
            for name in ("server.p12", "clients.p12", "client.p12", "servers.p12"):
                with self.subTest(store=name):
                    path = fix / name
                    original = path.read_bytes()
                    try:
                        path.write_bytes(b"not-a-pkcs12-store")
                        self.assertNotEqual(subprocess.run(command, capture_output=True, timeout=10).returncode, 0)
                    finally:
                        path.write_bytes(original)
            trusted = fix / "clients.p12"
            trusted.write_bytes((fix / "servers.p12").read_bytes())
            self.assertNotEqual(subprocess.run(command, capture_output=True, timeout=10).returncode, 0,
                                "A readable store trusting the wrong peer must be rejected")

    def test_input_fingerprint_tracks_policy_and_store_changes_without_exposing_values(self):
        with tempfile.TemporaryDirectory(prefix="query-certification-fingerprint-") as temporary:
            directory = Path(temporary) / "bundle"
            subprocess.run([sys.executable, str(PROVISIONER), str(directory)],
                           capture_output=True, check=True, timeout=60)
            command = [sys.executable, str(CLIENT), "--fixture-directory", str(directory), "--fingerprint"]
            initial = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual(initial.returncode, 0, "Input fingerprint must be supported")
            self.assertRegex(initial.stdout, r"^[0-9a-f]{64}\n$")
            policy = directory / "application.properties"
            policy.write_text(policy.read_text().replace("policy-version=1", "policy-version=2"))
            changed = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual(changed.returncode, 0)
            self.assertNotEqual(changed.stdout, initial.stdout)
            store = directory / "fix/server.p12"
            environment = json.loads((directory / "fix/client-environment.json").read_text())
            crypto_environment = dict(os.environ, FIX_TEST_STORE_PASSWORD=
                                      environment["SIMPLEMATCH_LIVE_FIX_KEY_STORE_PASSWORD"])
            pem = subprocess.run(["openssl", "pkcs12", "-in", str(store), "-nodes",
                                   "-passin", "env:FIX_TEST_STORE_PASSWORD"],
                                  env=crypto_environment, capture_output=True, check=True, timeout=10).stdout
            subprocess.run(["openssl", "pkcs12", "-export", "-name", "server", "-out", str(store),
                            "-passout", "env:FIX_TEST_STORE_PASSWORD"], input=pem,
                           env=crypto_environment, capture_output=True, check=True, timeout=10)
            final = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual(final.returncode, 0)
            self.assertNotEqual(final.stdout, changed.stdout)

    def test_generated_stores_enforce_mutual_tls_and_server_identity(self):
        with tempfile.TemporaryDirectory(prefix="query-certification-tls-") as temporary:
            directory = Path(temporary) / "bundle"
            subprocess.run([sys.executable, str(PROVISIONER), str(directory)],
                           capture_output=True, check=True, timeout=60)
            fix = directory / "fix"
            environment = json.loads((fix / "client-environment.json").read_text())
            crypto_environment = dict(os.environ, FIX_TEST_STORE_PASSWORD=
                                      environment["SIMPLEMATCH_LIVE_FIX_KEY_STORE_PASSWORD"])
            for name in ("server", "client", "servers", "clients"):
                exported = subprocess.run([
                    "openssl", "pkcs12", "-in", str(fix / (name + ".p12")),
                    "-passin", "env:FIX_TEST_STORE_PASSWORD", "-nodes"],
                    env=crypto_environment, capture_output=True, check=True, timeout=10)
                (fix / (name + ".pem")).write_bytes(exported.stdout)
            server = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            server.load_cert_chain(fix / "server.pem")
            server.load_verify_locations(cafile=fix / "clients.pem")
            server.verify_mode = ssl.CERT_REQUIRED
            for identity, hostname, accepted in (("client", "127.0.0.1", True),
                                                   (None, "127.0.0.1", False),
                                                   ("server", "127.0.0.1", False),
                                                   ("client", "unapproved.invalid", False)):
                with self.subTest(identity=identity, hostname=hostname):
                    listener = socket.socket()
                    listener.bind(("127.0.0.1", 0))
                    listener.listen(1)
                    listener.settimeout(3)
                    server_result = []

                    def serve():
                        try:
                            connection, _ = listener.accept()
                            connection.settimeout(3)
                            with connection, server.wrap_socket(connection, server_side=True) as peer:
                                self.assertEqual(peer.recv(4), b"test")
                                peer.sendall(b"ok")
                                server_result.append(True)
                        except (OSError, ssl.SSLError):
                            server_result.append(False)
                        finally:
                            listener.close()

                    client = ssl.create_default_context(cafile=fix / "servers.pem")
                    if identity:
                        client.load_cert_chain(fix / (identity + ".pem"))
                    address = listener.getsockname()
                    thread = threading.Thread(target=serve)
                    thread.start()
                    succeeded = False
                    try:
                        with socket.create_connection(address, timeout=3) as connection:
                            with client.wrap_socket(connection, server_hostname=hostname) as peer:
                                peer.sendall(b"test")
                                succeeded = peer.recv(2) == b"ok"
                    except (OSError, ssl.SSLError):
                        pass
                    finally:
                        thread.join(5)
                    self.assertFalse(thread.is_alive(), "TLS test must finish within its deadline")
                    self.assertEqual(succeeded, accepted)
                    self.assertEqual(server_result, [accepted])

    def test_mismatched_fixture_or_public_credentials_cannot_launch_a_client(self):
        with tempfile.TemporaryDirectory(prefix="query-certification-rejection-") as temporary:
            directory = Path(temporary) / "bundle"
            subprocess.run([sys.executable, str(PROVISIONER), str(directory)],
                           capture_output=True, check=True, timeout=60)
            marker = Path(temporary) / "must-not-execute"
            command = [sys.executable, str(CLIENT), "--fixture-directory", str(directory), "--exec",
                       sys.executable, "-c", "import pathlib,sys; pathlib.Path(sys.argv[1]).touch()",
                       str(marker)]
            identity = directory / "fix/fixture-identities.json"
            original = identity.read_text()
            changed = json.loads(original)
            changed["accounts"]["X"] = "11111111-1111-4111-8111-111111111111"
            identity.write_text(json.dumps(changed))
            self.assertNotEqual(subprocess.run(command, capture_output=True, timeout=10).returncode, 0)
            self.assertFalse(marker.exists())
            identity.write_text(original)
            (directory / "fix/client-environment.json").chmod(0o644)
            self.assertNotEqual(subprocess.run(command, capture_output=True, timeout=10).returncode, 0)
            self.assertFalse(marker.exists())

    def test_launcher_validates_bundle_and_passes_private_fix_credentials_only_in_environment(self):
        with tempfile.TemporaryDirectory(prefix="query-certification-client-") as temporary:
            directory = Path(temporary) / "bundle"
            subprocess.run([sys.executable, str(PROVISIONER), str(directory)],
                           capture_output=True, check=True, timeout=60)
            output = Path(temporary) / "observed.json"
            result = subprocess.run([
                sys.executable, str(CLIENT), "--fixture-directory", str(directory), "--exec",
                sys.executable, "-c",
                "import json,os,pathlib,sys; pathlib.Path(sys.argv[1]).write_text(json.dumps("
                "{key:value for key,value in os.environ.items() if key.startswith('SIMPLEMATCH_LIVE_FIX_')}))",
                str(output)], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, "Private client launcher must succeed")
            expected = json.loads((directory / "fix/client-environment.json").read_text())
            self.assertEqual(json.loads(output.read_text()), expected)
            for value in expected.values():
                self.assertNotIn(value, result.stdout + result.stderr)

    def test_provisions_one_private_query_matrix_and_independent_fix_material(self):
        with tempfile.TemporaryDirectory(prefix="query-certification-inputs-") as temporary:
            directory = Path(temporary) / "bundle"
            result = subprocess.run([sys.executable, str(PROVISIONER), str(directory)],
                                    capture_output=True, text=True, timeout=60)
            self.assertEqual(result.returncode, 0, "Certification provisioning must succeed")
            identities = json.loads((directory / "fixture-identities.json").read_text())
            credentials = json.loads((directory / "credentials.json").read_text())
            fix = directory / "fix"
            self.assertEqual(json.loads((fix / "fixture-identities.json").read_text()), identities)
            environment = json.loads((fix / "client-environment.json").read_text())
            password = environment["SIMPLEMATCH_LIVE_FIX_PASSWORD"]
            self.assertTrue(all(password != entry["token"] for entry in credentials.values()))
            properties = dict(line.split("=", 1) for line in
                              (fix / "application.properties").read_text().splitlines())
            prefix = "simplematch.quickfix-gateway.fix-security"
            self.assertEqual(properties[prefix + ".counterparties[0].session-id"],
                             "FIX.4.4:SIMPLEMATCH->CLIENT")
            self.assertEqual({value for key, value in properties.items() if ".account-ids[" in key},
                             set(identities["accounts"].values()))
            for name in ("server.p12", "clients.p12", "client.p12", "servers.p12"):
                self.assertGreater((fix / name).stat().st_size, 0)
            for path in (directory, *directory.rglob("*")):
                self.assertEqual(stat.S_IMODE(path.stat().st_mode),
                                 0o700 if path.is_dir() else 0o600)
            for value in (password, *(entry["token"] for entry in credentials.values())):
                self.assertNotIn(value, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
