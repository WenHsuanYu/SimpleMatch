#!/usr/bin/env python3
"""Generate one disposable, owner-private Query/FIX certification bundle before deployment."""

import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys

from lib.query_http import fixture


def tls_material(directory, password):
    """Trust exact synthetic peer certificates; this is not a production CA or PKI."""
    environment = dict(os.environ, SIMPLEMATCH_LAB_FIX_STORE_PASSWORD=password)

    def keytool(*arguments):
        subprocess.run(["keytool", *arguments, "-storetype", "PKCS12", "-storepass:env",
                        "SIMPLEMATCH_LAB_FIX_STORE_PASSWORD", "-noprompt"],
                       env=environment, capture_output=True, check=True, timeout=30)

    for name, usage in (("server", "serverAuth"), ("client", "clientAuth")):
        store = str(directory / (name + ".p12"))
        keytool("-genkeypair", "-keystore", store, "-alias", name, "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-ext", "EKU=" + usage)
        keytool("-exportcert", "-keystore", store, "-alias", name, "-rfc", "-file",
                str(directory / (name + ".crt")))
    for store, peer in (("clients", "client"), ("servers", "server")):
        keytool("-importcert", "-keystore", str(directory / (store + ".p12")),
                "-alias", peer, "-file", str(directory / (peer + ".crt")))


def provision(directory):
    """Reuse the approved Query provisioner and bind FIX's allowlist to its exact X/Y IDs."""
    query = directory / "query"
    subprocess.run([sys.executable, str(Path(__file__).with_name("provision-security-fixtures.py")),
                    str(query)], capture_output=True, check=True, timeout=15)
    for path in query.iterdir():
        path.rename(directory / path.name)
    query.rmdir()
    identities, _ = fixture(directory)
    fix = directory / "fix"
    fix.mkdir(mode=0o700)
    password = secrets.token_urlsafe(32)
    username = "certification-" + secrets.token_hex(8)
    store_password = secrets.token_urlsafe(32)
    tls_material(fix, store_password)
    prefix = "simplematch.quickfix-gateway.fix-security"
    properties = [
        f"{prefix}.key-store-password={store_password}",
        f"{prefix}.trust-store-password={store_password}",
        f"{prefix}.counterparties[0].session-id=FIX.4.4:SIMPLEMATCH->CLIENT",
        f"{prefix}.counterparties[0].username={username}",
        f"{prefix}.counterparties[0].password={password}",
        *(f"{prefix}.counterparties[0].account-ids[{index}]={identities['accounts'][alias]}"
          for index, alias in enumerate(("X", "Y"))),
    ]
    (fix / "application.properties").write_text("\n".join(properties) + "\n")
    (fix / "fixture-identities.json").write_text(json.dumps(identities, indent=2) + "\n")
    environment = {
        "SIMPLEMATCH_LIVE_FIX_USERNAME": username,
        "SIMPLEMATCH_LIVE_FIX_PASSWORD": password,
        "SIMPLEMATCH_LIVE_FIX_KEY_STORE": str(fix / "client.p12"),
        "SIMPLEMATCH_LIVE_FIX_KEY_STORE_PASSWORD": store_password,
        "SIMPLEMATCH_LIVE_FIX_TRUST_STORE": str(fix / "servers.p12"),
        "SIMPLEMATCH_LIVE_FIX_TRUST_STORE_PASSWORD": store_password,
    }
    (fix / "client-environment.json").write_text(json.dumps(environment, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output_directory", type=Path, help="new private synthetic Lab bundle")
    arguments = parser.parse_args()
    os.umask(0o077)
    directory = arguments.output_directory.absolute()
    if directory.exists() or directory.is_symlink() or not shutil.which("keytool"):
        print("Use a new output directory and install the JDK keytool.", file=sys.stderr)
        return 1
    created = False
    try:
        directory.mkdir(mode=0o700)
        created = True
        provision(directory)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        # Only this invocation's new disposable directory can be removed here.
        if created and directory.is_dir() and not directory.is_symlink():
            shutil.rmtree(directory)
        print("Cannot provision private synthetic certification material.", file=sys.stderr)
        return 1
    print("Created private Query/FIX certification fixture bundle: " + str(directory))
    return 0


if __name__ == "__main__":
    sys.exit(main())
