#!/usr/bin/env python3
"""Exercise real HTTP connection interruption without Kubernetes or a Query deployment."""

import http.client
import http.server
import importlib.util
import secrets
import socket
import socketserver
import struct
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


class LifecycleHttpContract(unittest.TestCase):
    def test_confirmed_original_connection_is_not_reused_during_other_replica_probes(self):
        spec = importlib.util.spec_from_file_location("query_lifecycle", Path(__file__).resolve().parents[1] / "security-lifecycle.py")
        lifecycle = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(lifecycle)
        ports, direct_b = {}, []

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def do_GET(self):
                port = self.client_address[1]
                ports[port] = ports.get(port, 0) + 1
                self.send_response(503)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def log_message(self, *_):
                pass

        def docker(arguments, **_):
            if "node-b" in arguments:
                direct_b.append(True)
                if len(direct_b) == 1:
                    return subprocess.CompletedProcess(arguments, 52, "\n000", "curl: Empty reply from server")
                status = "503"
            else:
                status = "503"
            return subprocess.CompletedProcess(arguments, 0, "\n" + status, "")

        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            originals = [http.client.HTTPConnection("127.0.0.1", server.server_address[1], timeout=2) for _ in range(2)]
            try:
                with tempfile.TemporaryDirectory(prefix="query-block-contract-") as temporary:
                    lab = Lab(Path(__file__).resolve().parents[4], Path(temporary) / "run", "lab", "lab", "query:test")
                    token = secrets.token_urlsafe(32)
                    endpoints = [{"pod": alias, "node": "node-" + alias, "ip": "127.0.0.1", "port": server.server_address[1]}
                                 for alias in ("a", "b")]
                    for endpoint, connection in zip(endpoints, originals):
                        lab.http(endpoint, "/baseline", token, connection)
                    original_ports = [connection.sock.getsockname()[1] for connection in originals]
                    probes = list(originals)
                    with patch("subprocess.run", side_effect=docker):
                        self.assertIsNone(lifecycle.blocked(lab, endpoints, token, probes))
                        self.assertIsNotNone(lifecycle.blocked(lab, endpoints, token, probes))
                    self.assertEqual([ports[port] for port in original_ports], [2, 2],
                                     "Each original connection gets one baseline and one rejection probe only")
            finally:
                for connection in originals:
                    connection.close()
                server.shutdown()
                thread.join(timeout=2)

    def test_repeated_probe_after_reset_does_not_get_request_sent(self):
        reading_body = threading.Event()
        requests = []

        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                headers = b""
                while b"\r\n\r\n" not in headers:
                    headers += self.request.recv(4096)
                requests.append(True)
                if len(requests) == 1:
                    self.request.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nx")
                    if not reading_body.wait(2):
                        raise RuntimeError("Client never started reading the response body")
                    self.request.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                    self.request.close()
                else:
                    self.request.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")

        class Response(http.client.HTTPResponse):
            def read(self, *arguments):
                reading_body.set()
                return super().read(*arguments)

        with socketserver.ThreadingTCPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            connection = http.client.HTTPConnection("127.0.0.1", server.server_address[1], timeout=2)
            connection.response_class = Response
            try:
                with tempfile.TemporaryDirectory(prefix="query-http-contract-") as temporary:
                    lab = Lab(Path(__file__).resolve().parents[4], Path(temporary) / "run", "lab", "lab", "query:test")
                    endpoint = {"port": server.server_address[1]}
                    token = secrets.token_urlsafe(32)
                    with self.assertRaises(ConnectionResetError):
                        lab.http(endpoint, "/resource", token, connection)
                    try:
                        lab.http(endpoint, "/resource", token, connection)
                    except ConnectionError:
                        pass  # The Block observer already treats a confirmed closed connection as rejected.
                    status, body, _ = lab.http(endpoint, "/resource", token, connection)
                    self.assertEqual((status, body), (200, b"{}"))
            finally:
                connection.close()
                server.shutdown()
                thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
