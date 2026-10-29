#!/usr/bin/env python3
"""Serve the approved operations identity for the complete placement command test."""
import http.server
import json
from pathlib import Path
import sys

bundle, port_file = map(Path, sys.argv[1:])
token = json.loads((bundle / "credentials.json").read_text())["operations"]["token"]


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if (self.path != "/api/v1/freshness"
                or self.headers.get("Authorization") != "Bearer " + token):
            self.send_error(401)
            return
        self.send_response(200)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b'{"partitions":[]}')

    def log_message(self, *_):
        pass


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
port_file.write_text(str(server.server_port))
server.serve_forever()
