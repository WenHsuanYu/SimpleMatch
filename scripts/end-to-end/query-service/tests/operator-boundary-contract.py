#!/usr/bin/env python3
"""Exercise the operator verification CLI through independent loopback HTTP outcomes."""

import http.server
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]


class OperatorBoundaryContract(unittest.TestCase):
    def test_verifier_rejects_read_credentials_with_operator_authority_and_leaks_no_credentials(self):
        with tempfile.TemporaryDirectory(prefix="query-operator-boundary-") as temporary:
            root = Path(temporary)
            bundle = root / "bundle"
            subprocess.run([sys.executable, str(ROOT / "provision-security-fixtures.py"), str(bundle)],
                           capture_output=True, check=True, timeout=10)
            operator = root / "operator.json"
            token = str(uuid.uuid4())
            operator.write_text(json.dumps({"token": token}))
            operator.chmod(0o600)

            class Handler(http.server.BaseHTTPRequestHandler):
                def do_POST(self):
                    self.send_response(200 if self.server.confused else 401)
                    self.send_header("Cache-Control", "no-store")
                    self.send_header("Content-Length", "0")
                    self.end_headers()

                do_GET = do_POST

                def log_message(self, *_):
                    pass

            with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                server.confused = False
                thread.start()
                try:
                    command = [sys.executable, str(ROOT / "lib/operator_boundary.py"),
                        "--fixture-directory", str(bundle), "--operator-token-file", str(operator),
                        "--base-url", "http://127.0.0.1:" + str(server.server_address[1]), "--output"]
                    report = root / "pass.json"
                    result = subprocess.run([*command, str(report)], capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, 0)
                    self.assertEqual(len(json.loads(report.read_text())["observations"]), 9)
                    self.assertNotIn(token, result.stdout + result.stderr + report.read_text())
                    server.confused = True
                    failed = root / "must-not-pass.json"
                    result = subprocess.run([*command, str(failed)], capture_output=True, text=True, timeout=10)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertFalse(failed.exists())
                finally:
                    server.shutdown()
                    thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
