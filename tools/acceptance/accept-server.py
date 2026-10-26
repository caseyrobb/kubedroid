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
# Optional bearer token: when set, every route except /version requires
# `Authorization: Bearer <token>` (spec 05 Testing: stub still requires the
# acceptance bearer token on every route, logs included).
REQUIRE_TOKEN = sys.argv[5] if len(sys.argv) > 5 else None


def log(line):
    stamp = datetime.now(timezone.utc).isoformat()
    with open(LOG, "a") as f:
        f.write(f"{stamp} {line}\n")


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # chunked log routes need 1.1 framing

    # ---- spec 04 canned payloads (paths matched without query string) ----
    @staticmethod
    def _pods(items, cont=None):
        meta = '{"continue": "%s"}' % cont if cont else "{}"
        return ('{"items":[%s],"metadata":%s}' % (",".join(items), meta)).encode()

    @staticmethod
    def _pod(name, phase="Running", ip="10.42.0.7"):
        return (
            '{"metadata":{"name":"%s","namespace":"web","labels":{"app":"frontend"},'
            '"creationTimestamp":"2026-01-01T00:00:00Z"},'
            '"spec":{"containers":[{"name":"c","image":"nginx"}]},'
            '"status":{"phase":"%s","podIP":"%s"}}' % (name, phase, ip)
        )

    @staticmethod
    def _pod_multi(name):
        return (
            '{"metadata":{"name":"%s","namespace":"web","labels":{"app":"multi"},'
            '"creationTimestamp":"2026-01-01T00:00:00Z"},'
            '"spec":{"containers":[{"name":"c","image":"nginx"},'
            '{"name":"sidecar","image":"envoy"}]},'
            '"status":{"phase":"Running","podIP":"10.42.0.9"}}' % name
        )

    # ---- spec 05 canned log routes ---------------------------------------
    DROPPED = set()  # pods whose follow stream aborts once (mid-stream drop)

    def _write_chunk(self, data):
        self.wfile.write(b"%X\r\n" % len(data))
        self.wfile.write(data + b"\r\n")
        self.wfile.flush()

    def _chunked_stream(self, tail_lines, appends=5, append_prefix="kblog-follow-"):
        """follow=true route: 1s-paced chunks, clean terminating chunk."""
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for ln in tail_lines:
            self._write_chunk((ln + "\n").encode())
            time.sleep(1)
        for i in range(1, appends + 1):
            self._write_chunk(
                f"2026-01-01T00:00:1{i}Z {append_prefix}{i}\n".encode())
            time.sleep(1)
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    def _chunked_abort(self, lines):
        """mid-stream drop: chunks land, then RST — client sees an IOException,
        not a clean EOF (no terminating chunk)."""
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for ln in lines:
            self._write_chunk((ln + "\n").encode())
        import socket as _socket
        import struct as _struct
        try:
            self.request.setsockopt(
                _socket.SOL_SOCKET, _socket.SO_LINGER, _struct.pack("ii", 1, 0))
            self.request.close()  # RST mid-body
        except OSError:
            pass

    def _log(self, base, query):
        """/api/v1/namespaces/web/pods/<name>/log routes (spec 05).

        Returns (code, body), the sentinel "sent" (response already written),
        or None."""
        container = ""
        for kv in query.split("&"):
            if kv.startswith("container="):
                container = kv.split("=", 1)[1]
        name = base.split("/")[6]
        if name == "web-nolog":
            return 403, (b'{"message":"pods \\"web-nolog\\" in namespace '
                         b'\\"web\\" is forbidden: cannot get pods/log"}')
        if name == "web-empty":
            return 200, b""
        if name == "web-multi":
            if "sidecar" in container:
                return 200, b"2026-01-01T00:00:01Z kblog-sidecar-only\n"
            return 200, b"2026-01-01T00:00:01Z kblog-multi-c\n"
        if name == "web-drop" and name not in self.DROPPED:
            self.DROPPED.add(name)
            self._chunked_abort(["2026-01-01T00:00:01Z kblog-drop-1",
                                 "2026-01-01T00:00:02Z kblog-drop-2"])
            return "sent"
        if name == "web-drop":
            return 200, (b"2026-01-01T00:00:01Z kblog-drop-1\n"
                         b"2026-01-01T00:00:02Z kblog-drop-2\n"
                         b"2026-01-01T00:00:03Z kblog-drop-3\n")
        if name == "web-1" and "follow=true" in query:
            self._chunked_stream(["2026-01-01T00:00:01Z kblog-tail-1",
                                  "2026-01-01T00:00:02Z kblog-tail-2"])
            return "sent"
        return 200, (b"2026-01-01T00:00:01Z kblog-alpha\n"
                     b"2026-01-01T00:00:02Z kblog-beta\n"
                     b"2026-01-01T00:00:03Z kblog-gamma\n")


    def _resource(self, path):
        """Return (code, body) for spec 04 canned routes, or None."""
        base = path.split("?")[0]
        query = path.split("?")[1] if "?" in path else ""
        if base == "/api/v1/namespaces":
            body = self._pods([
                '{"metadata":{"name":"default","creationTimestamp":"2026-01-01T00:00:00Z"}}',
                '{"metadata":{"name":"kube-system","creationTimestamp":"2026-01-01T00:00:00Z"}}',
            ])
            return 200, body
        if base == "/api/v1/secrets":
            return 403, b'{"message":"secrets is forbidden"}'  # 403 escape-hatch route
        if base == "/api/v1/namespaces/web/secrets/db":
            return 200, (
                '{"metadata":{"name":"db","namespace":"web","creationTimestamp":"2026-01-01T00:00:00Z"},'
                '"type":"Opaque","data":{"pw":"eA=="}}'
            ).encode()
        if base == "/apis/apps/v1/deployments":
            return 200, self._pods([
                '{"metadata":{"name":"web","namespace":"web","creationTimestamp":"2026-01-01T00:00:00Z"},'
                '"spec":{"replicas":2},"status":{"readyReplicas":2}}',
            ])
        if base == "/api/v1/pods":
            if "continue=PAGE2" in query:
                return 200, self._pods([self._pod("web-3", phase="Running")])
            return 200, self._pods([
                self._pod("web-1"),
                self._pod("web-2", phase="Pending"),
                self._pod_multi("web-multi"),
                self._pod("web-drop"),
                self._pod("web-nolog"),
            ], cont="PAGE2")
        if base.startswith("/api/v1/namespaces/web/pods/"):
            if base.endswith("/log"):
                return self._log(base, query)
            name = base.rsplit("/", 1)[-1]
            if name == "web-multi":
                return 200, self._pod_multi(name).encode()
            return 200, self._pod(name).encode()
        return None

    def do_GET(self):
        auth = self.headers.get("Authorization", "<none>")
        log(f"GET {self.path} Auth={auth}")
        if REQUIRE_TOKEN and self.path != "/version" \
                and auth != f"Bearer {REQUIRE_TOKEN}":
            body = b'{"message":"Unauthorized"}'
            self.send_response(401)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path == "/version":
            body = b'{"major":"1","minor":"36","gitVersion":"v1.36.4"}'
            self.send_response(200)
        else:
            canned = self._resource(self.path)
            if canned == "sent":
                return  # chunked log response already on the wire
            if canned is not None:
                code, body = canned
                self.send_response(code)
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
