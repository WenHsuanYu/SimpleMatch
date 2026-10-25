#!/usr/bin/env python3
"""Observe the existing certification input producer at its kubectl command seam."""

import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


REPOSITORY = Path(__file__).resolve().parents[4]
PROVISIONER = Path(__file__).resolve().parents[1] / "provision-certification-fixtures.py"
CLIENT = PROVISIONER.parent / "lib/certification_security.py"

# This command fixture implements only client-side input construction and server-side
# create/apply observation. It is not a Kubernetes or Spring authentication simulator.
KUBECTL = '''#!/usr/bin/env python3
import base64,json,os,pathlib,sys
root=pathlib.Path(os.environ["CAPTURE_DIRECTORY"])
args=sys.argv[1:]
with (root/"arguments.jsonl").open("a") as output:
    output.write(json.dumps(args)+"\\n")
if "-f" in args:
    source=args[args.index("-f")+1]
    if source=="-":
        obj=json.load(sys.stdin)
        (root/(obj["metadata"]["name"]+".json")).write_text(json.dumps(obj))
    else:
        obj={"apiVersion":"v1","kind":"ConfigMap","metadata":{"name":"input-dictionary"}}
    if "--dry-run=client" in args: print(json.dumps(obj))
    sys.exit(0)
if "create" in args:
    pos=args.index("create")
    kind=args[pos+1]
    name=args[pos+3] if kind=="secret" else args[pos+2]
    data={}
    for arg in args:
        if arg.startswith("--from-literal="):
            key,value=arg[len("--from-literal="):].split("=",1)
            data[key]=value
        if arg.startswith("--from-file="):
            key,path=arg[len("--from-file="):].split("=",1)
            data[key]=base64.b64encode(pathlib.Path(path).read_bytes()).decode()
    obj={"apiVersion":"v1","kind":"Secret" if kind=="secret" else "ConfigMap",
         "metadata":{"name":name},"data":data}
    print(json.dumps(obj))
    sys.exit(0)
if "get" in args and "secret" in args:
    names=args[args.index("secret")+1:args.index("-o")]
    print(json.dumps({"items":[json.loads((root/(name+".json")).read_text()) for name in names]}))
    sys.exit(0)
sys.exit(1)
'''


class CertificationInputsContract(unittest.TestCase):
    def test_full_runner_rejects_missing_bundle_before_invoking_docker(self):
        with tempfile.TemporaryDirectory(prefix="query-certification-early-rejection-") as temporary:
            root = Path(temporary)
            docker = root / "docker"
            marker = root / "must-not-invoke-docker"
            docker.write_text("#!/bin/sh\ntouch \"$CONTRACT_DOCKER_MARKER\"\nexit 1\n")
            docker.chmod(0o700)
            environment = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"],
                               CONTRACT_DOCKER_MARKER=str(marker))
            environment.pop("SIMPLEMATCH_CERTIFICATION_QUERY_FIXTURE_DIRECTORY", None)
            result = subprocess.run(["bash", str(REPOSITORY / "scripts/run-local-production-like-certification.sh")],
                                    env=environment, capture_output=True, text=True, timeout=15)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("--query-fixture-directory", result.stderr)
            self.assertFalse(marker.exists(), "Reject before any runtime/deployment action")

    def test_input_producer_creates_immutable_query_fix_and_tls_secrets_from_one_bundle(self):
        with tempfile.TemporaryDirectory(prefix="query-certification-kubernetes-") as temporary:
            root = Path(temporary)
            bundle = root / "bundle"
            subprocess.run([sys.executable, str(PROVISIONER), str(bundle)],
                           capture_output=True, check=True, timeout=60)
            executable = root / "kubectl"
            executable.write_text(KUBECTL)
            executable.chmod(0o700)
            delivery = root / "delivery.yaml"
            delivery.write_text("apiVersion: v1\nkind: ConfigMap\nmetadata:\n"
                                "  name: market-reference-20260811-approved\nimmutable: true\n")
            (root / "dictionary.yaml").write_text("unused client fixture\n")
            environment = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"],
                               CAPTURE_DIRECTORY=str(root), FIXTURE_DIRECTORY=str(bundle),
                               CONTRACT_DIRECTORY=str(root), REPOSITORY=str(REPOSITORY),
                               SIMPLEMATCH_MARKET_REFERENCE_DELIVERY_MANIFEST=str(delivery))
            environment["APPROVED_INPUT_DIGEST"] = subprocess.run([
                sys.executable, str(CLIENT), "--fixture-directory", str(bundle), "--fingerprint"],
                capture_output=True, text=True, check=True, timeout=10).stdout.strip()
            script = '''set -euo pipefail
repo_root="$REPOSITORY"
evidence_dir="$CONTRACT_DIRECTORY/evidence"
namespace=isolated-query-test
kind_context=kind-simplematch-live
certification_trading_day=2026-08-11
local_postgres_dsn=postgresql://synthetic@postgres/simplematch
local_postgres_password=synthetic
query_fixture_directory="$FIXTURE_DIRECTORY"
query_security_input_digest="$APPROVED_INPUT_DIGEST"
die() { printf 'input contract failed\\n' >&2; return 1; }
source "$repo_root/scripts/lib/local-certification-kubernetes.sh"
apply_local_kubernetes_inputs sha256:synthetic "$CONTRACT_DIRECTORY/dictionary.yaml"
'''
            result = subprocess.run(["bash", "-c", script], env=environment,
                                    capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, "Kubernetes input production must succeed")
            expected = {
                "query-service-secrets": {"application.properties": bundle / "application.properties"},
                "quickfix-gateway-secrets": {"application.properties": bundle / "fix/application.properties"},
                "quickfix-gateway-fix-tls": {name: bundle / "fix" / name
                                             for name in ("server.p12", "clients.p12")},
            }
            for name, files in expected.items():
                path = root / (name + ".json")
                self.assertTrue(path.exists(), "Missing required security Secret: " + name)
                secret = json.loads(path.read_text())
                self.assertIs(secret.get("immutable"), True)
                for key, source in files.items():
                    self.assertIn(key, secret["data"], "Missing application security configuration")
                    self.assertEqual(base64.b64decode(secret["data"][key]), source.read_bytes())
            arguments = (root / "arguments.jsonl").read_text()
            client = json.loads((bundle / "fix/client-environment.json").read_text())
            tokens = json.loads((bundle / "credentials.json").read_text())
            for value in (client["SIMPLEMATCH_LIVE_FIX_PASSWORD"],
                          client["SIMPLEMATCH_LIVE_FIX_KEY_STORE_PASSWORD"],
                          *(entry["token"] for entry in tokens.values())):
                self.assertNotIn(value, arguments + result.stdout + result.stderr)
            verified = subprocess.run([sys.executable, str(CLIENT), "--fixture-directory", str(bundle),
                                       "--verify-context", "kind-simplematch-live", "--verify-namespace",
                                       "isolated-query-test"], env=environment,
                                      capture_output=True, text=True, timeout=15)
            self.assertEqual(verified.returncode, 0, "Retained security inputs must validate")
            query = root / "query-service-secrets.json"
            stale = json.loads(query.read_text())
            stale["data"]["application.properties"] = base64.b64encode(b"stale-policy").decode()
            query.write_text(json.dumps(stale))
            rejected = subprocess.run([sys.executable, str(CLIENT), "--fixture-directory", str(bundle),
                                       "--verify-context", "kind-simplematch-live", "--verify-namespace",
                                       "isolated-query-test"], env=environment,
                                      capture_output=True, text=True, timeout=15)
            self.assertNotEqual(rejected.returncode, 0, "A different retained policy must be rejected")
            # A coherent replacement is still different from the already planned inputs.
            # Replaying the public input command must reject it before its first API action.
            (root / "arguments.jsonl").unlink()
            policy = bundle / "application.properties"
            policy.write_text(policy.read_text().replace("policy-version=1", "policy-version=2"))
            drifted = subprocess.run(["bash", "-c", script], env=environment,
                                     capture_output=True, text=True, timeout=15)
            self.assertNotEqual(drifted.returncode, 0, "Reject post-preflight security-input drift")
            self.assertFalse((root / "arguments.jsonl").exists(), "No mutation before drift rejection")


if __name__ == "__main__":
    unittest.main()
