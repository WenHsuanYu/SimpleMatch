#!/usr/bin/env python3
"""Validate and consume owner-private synthetic Query/FIX certification material."""

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import ssl
import subprocess
import sys
import tempfile

from query_http import fixture, private_json


def private_bytes(path):
    """Read a regular owner-private fixture file without publishing its contents."""
    info = path.lstat()
    if (path.is_symlink() or info.st_uid != os.getuid() or info.st_mode & 0o077
            or not stat.S_ISREG(info.st_mode)):
        raise ValueError("Certification material must remain owner-private")
    value = path.read_bytes()
    if not value:
        raise ValueError("Certification material must not be empty")
    return value


def load(directory):
    """Check fixture coherence; serving applications still authenticate and authorize independently."""
    identities, credentials = fixture(directory)
    # Reuse the Lab authority's controlled-format parser; inspection never approves policy.
    subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] / "policy-authority.py"),
                    "inspect-bundle", str(directory)],
                   capture_output=True, check=True, timeout=10)
    if any(datetime.fromisoformat(item["expiresAt"].replace("Z", "+00:00")) <= datetime.now(timezone.utc)
           for item in credentials.values()):
        raise ValueError("Generate fresh synthetic credentials before deployment")
    fix = directory / "fix"
    private_json(fix, directory=True)
    if private_json(fix / "fixture-identities.json") != identities:
        raise ValueError("FIX and Query must use the same approved fixture identities")
    environment = private_json(fix / "client-environment.json")
    expected_names = {"SIMPLEMATCH_LIVE_FIX_" + name for name in (
        "USERNAME", "PASSWORD", "KEY_STORE", "KEY_STORE_PASSWORD", "TRUST_STORE", "TRUST_STORE_PASSWORD")}
    if set(environment) != expected_names or any(not isinstance(value, str) or not value
                                               for value in environment.values()):
        raise ValueError("Incomplete synthetic FIX client configuration")
    for name, filename in (("KEY_STORE", "client.p12"), ("TRUST_STORE", "servers.p12")):
        if environment["SIMPLEMATCH_LIVE_FIX_" + name] != str(fix / filename):
            raise ValueError("FIX stores must belong to this private bundle")
    for name in ("PASSWORD", "KEY_STORE_PASSWORD", "TRUST_STORE_PASSWORD"):
        if not re.fullmatch(r"[A-Za-z0-9_-]{43}", environment["SIMPLEMATCH_LIVE_FIX_" + name]):
            raise ValueError("Expected generated synthetic FIX credentials")
    lines = private_bytes(fix / "application.properties").decode().splitlines()
    properties = dict(line.split("=", 1) for line in lines)
    prefix = "simplematch.quickfix-gateway.fix-security"
    expected = {
        prefix + ".key-store-password": environment["SIMPLEMATCH_LIVE_FIX_KEY_STORE_PASSWORD"],
        prefix + ".trust-store-password": environment["SIMPLEMATCH_LIVE_FIX_TRUST_STORE_PASSWORD"],
        prefix + ".counterparties[0].session-id": "FIX.4.4:SIMPLEMATCH->CLIENT",
        prefix + ".counterparties[0].username": environment["SIMPLEMATCH_LIVE_FIX_USERNAME"],
        prefix + ".counterparties[0].password": environment["SIMPLEMATCH_LIVE_FIX_PASSWORD"],
        **{prefix + f".counterparties[0].account-ids[{index}]": identities["accounts"][alias]
           for index, alias in enumerate(("X", "Y"))},
    }
    if len(lines) != len(properties) or properties != expected:
        raise ValueError("FIX configuration differs from the explicit fixture contract")
    for filename in ("server.p12", "clients.p12", "client.p12", "servers.p12"):
        private_bytes(fix / filename)
    validate_stores(fix, environment)
    return environment


def validate_stores(directory, client_environment):
    """Verify decryptability, private-key/certificate equality, exact peer trust and TLS profiles."""
    certificates = {}

    def openssl(*arguments, data=None, password=None):
        environment = dict(os.environ)
        if password is not None:
            environment["SIMPLEMATCH_LAB_FIX_STORE_PASSWORD"] = password
        return subprocess.run(["openssl", *arguments], input=data, env=environment,
                              capture_output=True, check=True, timeout=10).stdout

    for name in ("server", "client", "servers", "clients"):
        identity = name in {"server", "client"}
        password = client_environment["SIMPLEMATCH_LIVE_FIX_" + (
            "KEY_STORE_PASSWORD" if identity else "TRUST_STORE_PASSWORD")]
        pem = openssl("pkcs12", "-in", str(directory / (name + ".p12")), "-nodes",
                      "-passin", "env:SIMPLEMATCH_LAB_FIX_STORE_PASSWORD", password=password)
        certs = re.findall(rb"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", pem, re.S)
        if len(certs) != 1 or (b"PRIVATE KEY-----" in pem) != identity:
            raise ValueError("Expected one exact peer certificate and the required key-store type")
        certificates[name] = certs[0] + b"\n"
        if identity and openssl("pkey", "-pubout", data=pem) != openssl(
                "x509", "-pubkey", "-noout", data=certificates[name]):
            raise ValueError("TLS private key and certificate differ")
    for identity, trust in (("server", "servers"), ("client", "clients")):
        if ssl.PEM_cert_to_DER_cert(certificates[identity].decode()) != ssl.PEM_cert_to_DER_cert(
                certificates[trust].decode()):
            raise ValueError("Truststore does not contain the exact approved peer certificate")
    # Only public certificates are materialized here; private keys stay in memory.
    with tempfile.TemporaryDirectory(prefix="simplematch-fix-store-check-") as temporary:
        paths = {name: Path(temporary) / (name + ".crt") for name in certificates}
        for name, path in paths.items():
            with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as output:
                output.write(certificates[name])
        for identity, trust, purpose in (("server", "servers", "sslserver"),
                                          ("client", "clients", "sslclient")):
            arguments = ["verify", "-trusted", str(paths[trust]), "-purpose", purpose, "-check_ss_sig"]
            if identity == "server":
                arguments.extend(("-verify_ip", "127.0.0.1"))
            openssl(*arguments, str(paths[identity]))


def secret_files(directory):
    """Declare only the secret keys owned by this synthetic input producer."""
    return {
        "query-service-secrets": {"application.properties": directory / "application.properties"},
        "quickfix-gateway-secrets": {"application.properties": directory / "fix/application.properties"},
        "quickfix-gateway-fix-tls": {name: directory / "fix" / name
                                     for name in ("server.p12", "clients.p12")},
    }


def fingerprint(directory):
    """Hash declared private inputs; hashes are identity evidence, never policy approval."""
    paths = {path for files in secret_files(directory).values() for path in files.values()}
    paths.update(directory / name for name in (
        "credentials.json", "fixture-identities.json", "fix/fixture-identities.json",
        "fix/client-environment.json", "fix/client.p12", "fix/servers.p12"))
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(str(path.relative_to(directory)).encode() + b"\0")
        digest.update(private_bytes(path) + b"\0")
    return digest.hexdigest()


def verify_secrets(directory, context, namespace):
    """Reject retained inputs that differ from this run's private bundle, without logging values."""
    expected = secret_files(directory)
    result = subprocess.run(["kubectl", "--context", context, "--request-timeout=15s", "-n", namespace,
                             "get", "secret", *expected, "-o", "json"],
                            capture_output=True, check=True, timeout=20)
    secrets = {item["metadata"]["name"]: item for item in json.loads(result.stdout)["items"]}
    for name, files in expected.items():
        secret = secrets[name]
        if secret.get("immutable") is not True:
            raise ValueError("Certification security snapshots must be immutable")
        for key, path in files.items():
            if base64.b64decode(secret["data"][key], validate=True) != private_bytes(path):
                raise ValueError("Retained security inputs differ from this fixture bundle")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-directory", type=Path, required=True)
    parser.add_argument("--verify-context")
    parser.add_argument("--verify-namespace")
    parser.add_argument("--fingerprint", action="store_true")
    parser.add_argument("--exec", dest="command", nargs=argparse.REMAINDER)
    arguments = parser.parse_args()
    try:
        directory = arguments.fixture_directory.absolute()
        environment = load(directory)
        if arguments.fingerprint:
            if arguments.command is not None or arguments.verify_context or arguments.verify_namespace:
                raise ValueError("Fingerprinting is a separate operation")
            print(fingerprint(directory))
            return 0
        if bool(arguments.verify_context) != bool(arguments.verify_namespace):
            raise ValueError("Verification requires an explicit context and namespace")
        if arguments.verify_namespace:
            if arguments.command is not None:
                raise ValueError("Verify and execute are separate operations")
            verify_secrets(directory, arguments.verify_context, arguments.verify_namespace)
        if arguments.command is not None:
            if not arguments.command:
                raise ValueError("A client command is required")
            os.execvpe(arguments.command[0], arguments.command, dict(os.environ, **environment))
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print("Invalid, expired or mismatched private certification inputs.", file=sys.stderr)
        return 1
    print("Validated private synthetic Query/FIX certification inputs.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
