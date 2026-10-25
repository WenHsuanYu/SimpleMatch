#!/usr/bin/env python3
"""Real curl/TCP outcomes must distinguish rejection from an indeterminate direct probe."""

import json
import os
import secrets
import socket
import socketserver
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lib.security_lab import Lab


class LifecycleDirectContract(unittest.TestCase):
    def test_empty_reply_and_timeout_are_indeterminate_not_fatal_or_successful(self):
        self.exchange("empty", None)
        self.exchange("timeout", None)

    def test_http_rejection_and_live_http_are_distinguished(self):
        self.exchange("503", 503)
        self.exchange("400", 400)

    def test_partial_http_response_is_not_rejection(self):
        self.exchange("partial", None)

    def test_refused_connection_is_positive_rejection(self):
        self.exchange("refused", 0)

    def test_failed_observer_remains_indeterminate_and_preserves_private_diagnostics(self):
        self.exchange("observer", None)

    def exchange(self, mode, expected):
        actual_run = subprocess.run
        finish = threading.Event()

        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                headers = b""
                while b"\r\n\r\n" not in headers:
                    value = self.request.recv(4096)
                    if not value:
                        return
                    headers += value
                if mode == "timeout":
                    finish.wait(2)
                elif mode == "partial":
                    self.request.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nx")
                elif mode in ("400", "503"):
                    self.request.sendall(("HTTP/1.1 " + mode + " Status\r\nContent-Length: 0\r\n\r\n").encode())

        with socketserver.ThreadingTCPServer(("127.0.0.1", 0), Handler) as server:
            port = server.server_address[1]
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            if mode != "refused":
                thread.start()
            else:
                server.server_close()
            results = []

            def external(arguments, **options):
                self.assertEqual(arguments[0], "docker")
                if mode == "observer":
                    result = subprocess.CompletedProcess(arguments, 125, "", "Docker observation unavailable")
                else:
                    configuration = options["input"].replace("http://127.0.0.1:8086", "http://127.0.0.1:" + str(port))
                    environment = {key: value for key, value in os.environ.items() if not key.lower().endswith("_proxy")}
                    environment.update(http_proxy="http://127.0.0.1:1", all_proxy="http://127.0.0.1:1", no_proxy="")
                    result = actual_run(arguments[arguments.index("curl"):],
                        **dict(options, input=configuration, env=environment))
                results.append(result.returncode)
                return result

            try:
                with tempfile.TemporaryDirectory(prefix="query-direct-contract-") as temporary:
                    lab = Lab(Path(__file__).resolve().parents[4], Path(temporary) / "run", "lab", "lab", "query:test")
                    with patch("subprocess.run", side_effect=external):
                        observed = lab.direct_status({"node": "worker", "ip": "127.0.0.1"}, secrets.token_urlsafe(32))
                    self.assertEqual(observed, expected)
                    if expected is None:
                        files = list(lab.private.glob("direct-probe-*.json"))
                        self.assertEqual(len(files), 1)
                        self.assertEqual(json.loads(files[0].read_text())["exitCode"], results[0])
            finally:
                finish.set()
                if thread.is_alive():
                    server.shutdown()
                    thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
