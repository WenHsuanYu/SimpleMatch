#!/usr/bin/env python3
"""Exercise the certification matrix CLI against an independent credential-checking TCP server."""

import http.server
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid


ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / "lib/read_matrix.py"


class ReadMatrixContract(unittest.TestCase):
    def test_five_principals_cover_account_order_execution_and_empty_parent_on_two_accounts(self):
        with tempfile.TemporaryDirectory(prefix="query-read-matrix-") as temporary:
            root = Path(temporary)
            bundle = root / "bundle"
            subprocess.run([sys.executable, str(ROOT / "provision-security-fixtures.py"), str(bundle)],
                           capture_output=True, check=True, timeout=10)
            identities = json.loads((bundle / "fixture-identities.json").read_text())
            credentials = json.loads((bundle / "credentials.json").read_text())
            tokens = {entry["token"]: alias for alias, entry in credentials.items()}
            resources = {"orders": [{"account": account, "orderId": str(uuid.uuid4()),
                                      "hasExecutions": filled} for account in ("X", "Y") for filled in (True, False)],
                         "missingOrderId": str(uuid.uuid4()),
                         "market": {"tradingDay": "2026-08-11", "venueMic": "XTAI", "symbol": "2330",
                                    "artifactId": "synthetic-artifact"}}
            source = root / "resources.json"
            source.write_text(json.dumps(resources))
            source.chmod(0o600)
            observed = []

            class Handler(http.server.BaseHTTPRequestHandler):
                def do_GET(self):
                    alias = tokens.get(self.headers.get("Authorization", "").removeprefix("Bearer "))
                    status, body = 401, None
                    if alias:
                        parts = self.path.split("/")
                        grants = {"client-a": {"X"}, "client-b": {"Y"},
                                  "client-c": {"X", "Y"} if self.server.profile == "initial" else {"X"},
                                  "operations": set(), "client-d": set()}[alias]
                        if self.server.profile == "d-disabled" and alias == "client-d":
                            alias = None
                    if alias:
                        if self.path == "/api/v1/freshness":
                            status, body = (200, {"partitions": []}) if alias == "operations" else (403, None)
                        elif parts[3] == "market-reference":
                            status, body = 200, resources["market"]
                        else:
                            order = next((row for row in resources["orders"] if row["orderId"] == parts[4]), None)
                            account = (next((key for key, value in identities["accounts"].items()
                                             if value == parts[4]), None) if parts[3] == "accounts"
                                       else order["account"] if order else None)
                            status = 200 if account in grants else 404
                            if status == 200:
                                body = {"accountId": identities["accounts"][account]}
                                if order:
                                    body["orderId"] = order["orderId"]
                                    if self.path.endswith("/executions"):
                                        body = [body] if order["hasExecutions"] else []
                                        if self.server.poison and body:
                                            body[0]["accountId"] = identities["accounts"]["Y" if account == "X" else "X"]
                        if status == 200 and self.path != "/api/v1/freshness":
                            body = {"data": body, "freshness": {"projectionState": "NO_GAP_RECORDED"}}
                    observed.append((alias, self.path, status))
                    raw = b"" if body is None else json.dumps(body).encode()
                    self.send_response(status)
                    self.send_header("Content-Length", str(len(raw)))
                    self.send_header("Cache-Control", "no-store")
                    if alias:
                        self.send_header("X-SimpleMatch-Query-Policy-Version", str(self.server.version))
                    self.end_headers()
                    self.wfile.write(raw)

                def log_message(self, *_):
                    pass

            with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
                server.profile, server.version, server.poison = "initial", 1, False
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                try:
                    output = root / "report.json"
                    result = subprocess.run([sys.executable, str(TOOL), "--fixture-directory", str(bundle),
                                             "--resources", str(source), "--base-url",
                                             "http://127.0.0.1:" + str(server.server_address[1]),
                                             "--policy-version", "1", "--profile", "initial",
                                             "--output", str(output)], capture_output=True, text=True, timeout=20)
                    self.assertEqual(result.returncode, 0, "Complete read matrix must pass")
                    report = json.loads(output.read_text())
                    self.assertEqual(report["status"], "PASS")
                    for account in ("X", "Y"):
                        for row in [item for item in resources["orders"] if item["account"] == account]:
                            path = "/api/v1/orders/" + row["orderId"] + "/executions"
                            self.assertIn(("client-c", path, 200), observed)
                            self.assertIn(("operations", path, 404), observed)
                    self.assertIn(("client-d", "/api/v1/orders/" + resources["missingOrderId"] + "/executions", 404), observed)
                    for token in tokens:
                        self.assertNotIn(token, result.stdout + result.stderr + output.read_text())
                    for profile, version in (("c-y-revoked", 2), ("d-disabled", 3)):
                        server.profile, server.version = profile, version
                        output = root / (profile + ".json")
                        next_result = subprocess.run([
                            sys.executable, str(TOOL), "--fixture-directory", str(bundle),
                            "--resources", str(source), "--base-url", "http://127.0.0.1:" + str(server.server_address[1]),
                            "--policy-version", str(version), "--profile", profile, "--output", str(output)],
                            capture_output=True, text=True, timeout=20)
                        self.assertEqual(next_result.returncode, 0, "Revoked/disabled matrix must be checked")
                        cases = json.loads(output.read_text())["observations"]
                        self.assertTrue(all(row["status"] == 404 for row in cases
                                            if row["principal"] == "client-c" and row["account"] == "Y"))
                        self.assertTrue(all(row["status"] == 200 for row in cases
                                            if row["principal"] == "client-c" and row["account"] == "X"))
                        self.assertTrue(all(row["status"] == 200 for row in cases
                                            if row["principal"] == "client-b" and row["account"] == "Y"))
                    server.poison = True
                    failed = subprocess.run([
                        sys.executable, str(TOOL), "--fixture-directory", str(bundle), "--resources", str(source),
                        "--base-url", "http://127.0.0.1:" + str(server.server_address[1]), "--policy-version", "3",
                        "--profile", "d-disabled", "--output", str(root / "must-not-pass.json")],
                        capture_output=True, text=True, timeout=20)
                    self.assertNotEqual(failed.returncode, 0, "Mixed-account executions must invalidate evidence")
                    self.assertFalse((root / "must-not-pass.json").exists())
                finally:
                    server.shutdown()
                    thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
