#!/usr/bin/env python3
"""Exercise the existing collectors against an independent authenticated HTTP fixture."""

import http.server
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest


REPOSITORY = Path(__file__).resolve().parents[4]
MODULE = REPOSITORY / "scripts/end-to-end/query-service/lib/evidence.sh"
PLACEMENT = REPOSITORY / "scripts/lib/local-java-placement-serving.sh"
PROVISIONER = REPOSITORY / "scripts/end-to-end/query-service/provision-security-fixtures.py"


class CollectorHttpContract(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="query-collector-http-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bundle = self.root / "bundle"
        subprocess.run(["python3", str(PROVISIONER), str(self.bundle)],
                       check=True, capture_output=True)
        self.credentials = json.loads((self.bundle / "credentials.json").read_text())
        self.identities = json.loads((self.bundle / "fixture-identities.json").read_text())
        self.account = self.identities["accounts"]["X"]
        self.order = "00000000-0000-4000-8000-000000000019"
        self.paths = {
            f"/api/v1/orders/{self.order}": ("client-a", {
                "data": {"orderId": self.order, "accountId": self.account},
                "freshness": {"projectionState": "NO_GAP_RECORDED"}}),
            f"/api/v1/orders/{self.order}/executions": ("client-a", {
                "data": [{"orderId": self.order, "accountId": self.account}],
                "freshness": {"projectionState": "NO_GAP_RECORDED"}}),
            f"/api/v1/accounts/{self.account}/summary": ("client-a", {
                "data": {"accountId": self.account},
                "freshness": {"projectionState": "NO_GAP_RECORDED"}}),
            "/api/v1/market-reference/2026-08-11/XTAI/2330": ("client-d", {
                "data": {"tradingDay": "2026-08-11", "venueMic": "XTAI", "symbol": "2330"},
                "freshness": {"projectionState": "NO_GAP_RECORDED"}}),
            "/api/v1/freshness": ("operations", {"partitions": []}),
            "/actuator/health/readiness": (None, {"status": "UP"}),
            "/actuator/health/liveness": (None, {"status": "UP"}),
        }
        self.requests = []
        self.cache_control = "no-store"
        self.stream_delay = 0
        fixture = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                expected = fixture.paths.get(self.path)
                fixture.requests.append((self.path, self.headers.get("Authorization")))
                if expected is None:
                    self.send_response(404)
                    self.end_headers()
                    return
                principal, body = expected
                authorization = ("Bearer " + fixture.credentials[principal]["token"]
                                 if principal else None)
                if self.headers.get("Authorization") != authorization:
                    self.send_response(401)
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header("Cache-Control", fixture.cache_control)
                self.end_headers()
                payload = body if isinstance(body, bytes) else json.dumps(body).encode()
                try:
                    if fixture.stream_delay:
                        for byte in payload:
                            self.wfile.write(bytes([byte]))
                            self.wfile.flush()
                            time.sleep(fixture.stream_delay)
                    else:
                        self.wfile.write(payload)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # Expected when a deadline closes the client socket.

            def log_message(self, *_):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.evidence = self.root / "evidence"
        (self.evidence / "diagnostics").mkdir(parents=True)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def collect(self, command):
        environment = os.environ | {
            "MODULE": str(MODULE), "evidence_dir": str(self.evidence),
            "PLACEMENT": str(PLACEMENT),
            "query_fixture_directory": str(self.bundle),
            "query_port": str(self.server.server_port), "account_id": self.account,
            "order_id": self.order, "trading_day": "2026-08-11", "venue_mic": "XTAI",
            "symbol": "2330", "destination": str(self.evidence / "snapshot.json"),
        }
        result = subprocess.run(["bash", "-xeuc", 'source "$MODULE"\n' + command],
                                env=environment, capture_output=True, text=True, timeout=20)
        self.assertFalse(any(credential["token"] in result.stdout + result.stderr
                             for credential in self.credentials.values()),
                         "A private synthetic credential appeared in command output")
        return result

    def test_snapshot_uses_fixture_granted_business_identity_and_independent_operations(self):
        result = self.collect('capture_query_snapshot "$destination"')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.requests), 5)
        snapshot = json.loads((self.evidence / "snapshot.json").read_text())
        self.assertEqual(snapshot["accountSummary"]["data"]["accountId"], self.account)
        self.assertEqual(snapshot["freshness"], {"partitions": []})

    def test_business_collector_rejects_old_embedded_checkpoint_envelopes(self):
        for path, (principal, body) in list(self.paths.items()):
            if "data" not in body:
                continue
            with self.subTest(path=path):
                self.paths[path] = (principal, body | {"freshness": {"partitions": []}})
                result = self.collect('fetch_query_json "' + path + '" "$destination"')
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.evidence / "snapshot.json").exists())
                self.paths[path] = (principal, body)

    def test_execution_collector_rejects_mixed_account_or_order_collection(self):
        path = f"/api/v1/orders/{self.order}/executions"
        principal, body = self.paths[path]
        for invalid in ({"orderId": self.order, "accountId": self.identities["accounts"]["Y"]},
                        {"orderId": "00000000-0000-4000-8000-000000000020", "accountId": self.account}):
            with self.subTest(invalid=invalid):
                self.paths[path] = (principal, body | {"data": [body["data"][0], invalid]})
                result = self.collect('fetch_query_json "' + path + '" "$destination"')
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.evidence / "snapshot.json").exists())

    def test_unapproved_fixture_account_is_rejected_before_http(self):
        result = self.collect('account_id=00000000-0000-4000-8000-000000000099\n'
                              'fetch_query_json "/api/v1/orders/$order_id" "$destination"')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.requests, [])

    def test_no_store_must_be_a_real_cache_control_directive(self):
        self.cache_control = "private, x-no-store"
        result = self.collect('fetch_query_json "/api/v1/freshness" "$destination"')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.evidence / "snapshot.json").exists())

    def test_malformed_json_is_not_retained_as_evidence(self):
        self.paths["/api/v1/freshness"] = ("operations", b'{"partitions":')
        result = self.collect('fetch_query_json "/api/v1/freshness" "$destination"')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.evidence / "snapshot.json").exists())

    def test_total_deadline_stops_a_slowly_streaming_response(self):
        self.stream_delay = 0.08  # Each byte arrives inside the per-socket timeout.
        output = self.evidence / "slow-response.json"
        result = subprocess.run([
            "python3", str(MODULE.with_name("query_http.py")),
            "--fixture-directory", str(self.bundle), "--base-url",
            f"http://127.0.0.1:{self.server.server_port}", "--path", "/api/v1/freshness",
            "--timeout-seconds", "0.25", "--output", str(output),
        ], capture_output=True, text=True, timeout=5)
        self.assertNotEqual(result.returncode, 0, "Slow streaming exceeded the total request budget")
        self.assertFalse(output.exists(), "Timed-out responses must not become evidence")

    def test_placement_serving_uses_operations_and_health_remains_anonymous(self):
        (self.evidence / "health").mkdir()
        result = self.collect('''source "$PLACEMENT"
remaining_seconds() { printf '20\\n'; }
run_bounded() { "$@"; }
die() { printf '%s\\n' "$*" >&2; exit 1; }
port_forward_port="$query_port"
java_placement_serving_capture_http_probe baseline serving /api/v1/freshness >"$evidence_dir/serving-probe.json"
java_placement_serving_capture_http_probe baseline readiness /actuator/health/readiness >"$evidence_dir/readiness-probe.json"
java_placement_serving_capture_http_probe baseline liveness /actuator/health/liveness >"$evidence_dir/liveness-probe.json"
''')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([path for path, _ in self.requests], [
            "/api/v1/freshness", "/actuator/health/readiness", "/actuator/health/liveness"])
        self.assertTrue(all(header is None for _, header in self.requests[1:]))
        serving = json.loads((self.evidence / "serving-probe.json").read_text())
        self.assertEqual(serving.get("authentication"), "query-operations-read")
        for name in ("readiness", "liveness"):
            probe = json.loads((self.evidence / f"{name}-probe.json").read_text())
            self.assertEqual(probe.get("authentication"), "anonymous-health")

    def test_placement_accepts_its_supported_600_second_observer_budget(self):
        (self.evidence / "health").mkdir()
        result = self.collect('''source "$PLACEMENT"
remaining_seconds() { printf '600\\n'; }
run_bounded() { "$@"; }
die() { printf '%s\\n' "$*" >&2; exit 1; }
port_forward_port="$query_port"
java_placement_serving_capture_http_probe baseline serving /api/v1/freshness
''')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.requests), 1)


if __name__ == "__main__":
    unittest.main()
