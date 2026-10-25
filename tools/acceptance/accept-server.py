#!/usr/bin/env python3
"""Acceptance HTTPS stub for kubedroid spec 03 (devbox only, throwaway).

Serves /version like a kube-apiserver and logs every request line +
Authorization header so tools/acceptance/acceptance.py can assert the app
actually contacted the server with the right credentials over TLS.

Usage: accept-server.py PORT LOG CERT KEY
"""
import http.server
import ssl
import sys
import time
from datetime import datetime, timezone

PORT = int(sys.argv[1])
LOG = sys.argv[2]
CERT = sys.argv[3]
KEY = sys.argv[4]


def log(line):
    stamp = datetime.now(timezone.utc).isoformat()
    with open(LOG, "a") as f:
        f.write(f"{stamp} {line}\n")


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        auth = self.headers.get("Authorization", "<none>")
        log(f"GET {self.path} Auth={auth}")
        if self.path == "/version":
            body = b'{"major":"1","minor":"36","gitVersion":"v1.36.4"}'
            self.send_response(200)
        else:
            body = b"{}"
            self.send_response(404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args, **kwargs):  # silence stderr noise
        pass


ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(CERT, KEY)


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    ssl_handshake_timeout = 5

    def handle_error(self, request, client_address):
        # surface thread exceptions in the acceptance log instead of stderr
        import traceback
        log(f"HANDLE_ERROR {client_address}: "
            f"{traceback.format_exc(limit=3).strip()[:300]}")


httpd = Server(("0.0.0.0", PORT), Handler)
httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
log(f"SERVER_START port={PORT}")
while True:  # survive transient accept-loop errors (slirp resets etc.)
    try:
        httpd.serve_forever()
    except Exception as exc:  # noqa: BLE001 — acceptance stub, keep serving
        log(f"SERVE_LOOP_RESTART {exc!r}")
        time.sleep(0.5)
