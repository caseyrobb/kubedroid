#!/usr/bin/env python3
"""Acceptance HTTPS stub for kubedroid spec 03 (devbox only, throwaway).

Serves /version like a kube-apiserver and logs every request line +
Authorization header so tools/acceptance/acceptance.py can assert the app
actually contacted the server with the right credentials over TLS.

Usage: accept-server.py PORT LOG CERT KEY
"""
import http.server
import json
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
            '{"apiVersion":"v1","kind":"Pod",'
            '"metadata":{"name":"%s","namespace":"web","labels":{"app":"frontend"},'
            '"creationTimestamp":"2026-01-01T00:00:00Z"},'
            '"spec":{"containers":[{"name":"c","image":"nginx"}]},'
            '"status":{"phase":"%s","podIP":"%s"}}' % (name, phase, ip)
        )

    @staticmethod
    def _pod_multi(name):
        return (
            '{"apiVersion":"v1","kind":"Pod",'
            '"metadata":{"name":"%s","namespace":"web","labels":{"app":"multi"},'
            '"creationTimestamp":"2026-01-01T00:00:00Z"},'
            '"spec":{"containers":[{"name":"c","image":"nginx"},'
            '{"name":"sidecar","image":"envoy"}]},'
            '"status":{"phase":"Running","podIP":"10.42.0.9"}}' % name
        )

    # ---- spec 05 canned log routes ---------------------------------------
    DROPPED = set()  # pods whose follow stream aborts once (mid-stream drop)

    # ---- spec 06 mutable store -------------------------------------------
    # Pods that the acceptance driver edits/deletes live here (lazy seed from
    # the static fixtures, each with a server-managed resourceVersion).
    # web-protected exists solely to exercise the delete-403 path.
    MUT: dict = {}
    DELETED = set()
    PUTS = {}
    RV = {}
    MUT_NAMES = ("web-1", "web-2", "web-multi", "web-drop", "web-nolog",
                 "web-protected")

    # ---- spec 07 CRD discovery + CR mutable store --------------------------
    CRD_403 = [False]  # toggled via /__test__/crd403/on|off for the 403 step
    WIDGET_MUT: dict = {}
    WIDGET_PUTS = {}
    WIDGET_RV = {}
    WIDGET_NAMES = ("wid-1", "wid-2")

    @staticmethod
    def _widget(name):
        return {
            "apiVersion": "example.com/v2", "kind": "Widget",
            "metadata": {"name": name, "namespace": "web",
                         "creationTimestamp": "2026-03-01T00:00:00Z"},
            "spec": {"replicas": 3, "image": "widget:1"},
            "status": {"phase": f"Active-{name}",
                       "conditions": [{"type": "Ready", "status": "True"}]},
        }

    @classmethod
    def _seed_widgets(cls):
        if cls.WIDGET_MUT:
            return
        for i, name in enumerate(cls.WIDGET_NAMES):
            body = cls._widget(name)
            body["metadata"]["resourceVersion"] = str(2000 + i)
            cls.WIDGET_MUT[name] = body
            cls.WIDGET_RV[name] = 2000 + i

    @staticmethod
    def _crds():
        widget_crd = {
            "apiVersion": "apiextensions.k8s.io/v1",
            "kind": "CustomResourceDefinition",
            "metadata": {"name": "widgets.example.com",
                         "creationTimestamp": "2026-03-01T00:00:00Z"},
            "spec": {
                "group": "example.com", "scope": "Namespaced",
                "names": {"plural": "widgets", "kind": "Widget",
                          "singular": "widget", "listKind": "WidgetList",
                          "shortNames": ["wid"]},
                "versions": [
                    {"name": "v1", "served": True, "storage": False},
                    {"name": "v2", "served": True, "storage": True,
                     "additionalPrinterColumns": [
                         {"name": "Replicas", "type": "integer",
                          "jsonPath": "{.spec.replicas}"},
                         {"name": "Phase", "type": "string",
                          "jsonPath": "{.status.phase}"}]},
                ],
            },
        }
        cluster_crd = {
            "apiVersion": "apiextensions.k8s.io/v1",
            "kind": "CustomResourceDefinition",
            "metadata": {"name": "clusters.stable.example.com",
                         "creationTimestamp": "2026-03-01T00:00:00Z"},
            "spec": {
                "group": "stable.example.com", "scope": "Cluster",
                "names": {"plural": "clusters", "kind": "Cluster",
                          "listKind": "ClusterList"},
                "versions": [{"name": "v1", "served": True, "storage": True}],
            },
        }
        broken = {"metadata": {"name": "broken.example.com"},
                  "spec": {"group": "example.com"}}  # must be skipped by app
        return [widget_crd, broken, cluster_crd]

    @classmethod
    def _seed(cls):
        if cls.MUT:  # seeded once (MUT is never left empty after seeding)
            return
        cls.MUT = {}
        for i, name in enumerate(cls.MUT_NAMES):
            raw = (cls._pod_multi(name) if name == "web-multi"
                   else cls._pod(name, phase="Pending" if name == "web-2" else "Running"))
            body = json.loads(raw)
            body["metadata"]["resourceVersion"] = str(1000 + i)
            cls.MUT[name] = body
            cls.RV[name] = 1000 + i

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _auth_ok(self, verb):
        auth = self.headers.get("Authorization", "<none>")
        log(f"{verb} {self.path} Auth={auth}")
        if REQUIRE_TOKEN and auth != f"Bearer {REQUIRE_TOKEN}":
            self._json(401, {"message": "Unauthorized"})
            return False
        return True

    def _item_route(self):
        base = self.path.split("?")[0]
        prefix = "/api/v1/namespaces/web/pods/"
        if base.startswith(prefix) and not base.endswith("/log"):
            return base[len(prefix):] or None
        return None

    def do_PUT(self):
        if not self._auth_ok("PUT"):
            return
        # spec 07: CR PUT (widgets store) — same RV-precondition semantics.
        if self.path.split("?")[0].startswith("/apis/example.com/v2/widgets/") or \
                "/namespaces/web/widgets/" in self.path:
            name = self.path.split("?")[0].rsplit("/", 1)[-1]
            self._seed_widgets()
            if name not in self.WIDGET_MUT:
                return self._json(404, {"kind": "Status", "reason": "NotFound",
                                        "code": 404, "message": "no such route"})
            self.WIDGET_PUTS[name] = self.WIDGET_PUTS.get(name, 0) + 1
            n = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(n) if n else b""
            try:
                body = json.loads(raw.decode())
            except Exception:  # noqa: BLE001
                return self._json(422, {"kind": "Status", "reason": "Invalid",
                                        "code": 422, "message": "body is not valid JSON"})
            want = self.WIDGET_RV[name]
            if (body.get("metadata") or {}).get("resourceVersion") != str(want):
                return self._json(409, {
                    "kind": "Status", "reason": "Conflict", "code": 409,
                    "message": "the object has been modified; please apply your "
                               "changes to the latest version of the object"})
            if not isinstance((body.get("spec") or {}).get("replicas"), int):
                return self._json(422, {"kind": "Status", "reason": "Invalid",
                                        "code": 422,
                                        "message": "spec.replicas: Required value"})
            body.setdefault("status", self.WIDGET_MUT[name].get("status"))
            self.WIDGET_RV[name] = want + 1
            body["metadata"]["resourceVersion"] = str(self.WIDGET_RV[name])
            self.WIDGET_MUT[name] = body
            log(f"CRD_PUT {name} rv={self.WIDGET_RV[name]} puts={self.WIDGET_PUTS[name]}")
            return self._json(200, body)
        name = self._item_route()
        self._seed()
        n = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(n) if n else b""
        if name is None or name not in self.MUT:
            return self._json(404, {"kind": "Status", "reason": "NotFound",
                                    "code": 404, "message": "no such route"})
        self.PUTS[name] = self.PUTS.get(name, 0) + 1
        try:
            body = json.loads(raw.decode())
        except Exception:  # noqa: BLE001 — malformed body is a client bug
            return self._json(422, {"kind": "Status", "reason": "Invalid",
                                    "code": 422, "message": "body is not valid JSON"})
        want = self.RV[name]
        if (body.get("metadata") or {}).get("resourceVersion") != str(want):
            return self._json(409, {
                "kind": "Status", "reason": "Conflict", "code": 409,
                "message": "the object has been modified; please apply your "
                           "changes to the latest version of the object"})
        containers = (body.get("spec") or {}).get("containers") or []
        if not containers or any(not c.get("image") for c in containers):
            return self._json(422, {
                "kind": "Status", "reason": "Invalid", "code": 422,
                "message": "spec.containers: Required value"})
        # apiserver fidelity: status is a subresource — a main-resource PUT
        # never clears it (driver's replacement doc omits status on purpose)
        body.setdefault("status", self.MUT[name].get("status"))
        self.RV[name] = want + 1
        body["metadata"]["resourceVersion"] = str(self.RV[name])
        self.MUT[name] = body
        log(f"MUT_PUT {name} rv={self.RV[name]} puts={self.PUTS[name]}")
        return self._json(200, body)

    def do_DELETE(self):
        if not self._auth_ok("DELETE"):
            return
        name = self._item_route()
        self._seed()
        if name is None or name not in self.MUT:
            return self._json(404, {"kind": "Status", "reason": "NotFound",
                                    "code": 404, "message": "no such route"})
        if name == "web-protected":  # RBAC gate on delete (verb in message)
            return self._json(403, {
                "kind": "Status", "reason": "Forbidden", "code": 403,
                "message": f'pods "{name}" in namespace "web" is forbidden: '
                           f"User cannot delete resource pods"})
        if name in self.DELETED:  # second delete: apiserver 404
            return self._json(404, {"kind": "Status", "reason": "NotFound",
                                    "code": 404,
                                    "message": f'pods "{name}" not found'})
        self.DELETED.add(name)
        log(f"MUT_DELETE {name}")
        return self._json(200, {"kind": "Status", "status": "Success",
                                "code": 200})


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
        """mid-stream drop: chunks land but the terminating chunk never comes;
        the connection then closes cleanly (TLS close_notify + FIN). OkHttp's
        chunked reader hits EOF mid-body -> ProtocolException (IOException)
        -> Network error + buffer kept (spec 05 manual-retry decision).
        NOTE: a hard RST (SO_LINGER 0) does NOT work behind `adb reverse` —
        the tunnel swallows abrupt resets and the client blocks forever with
        no exception (verified in the API-26 acceptance run)."""
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for ln in lines:
            self._write_chunk((ln + "\n").encode())
        self.wfile.flush()
        self.close_connection = True  # finish() closes cleanly -> FIN EOF

    def _log(self, base, query):
        """/api/v1/namespaces/web/pods/<name>/log routes (spec 05).

        Returns (code, body), the sentinel "sent" (response already written),
        or None."""
        container = ""
        for kv in query.split("&"):
            if kv.startswith("container="):
                container = kv.split("=", 1)[1]
        # Realistic content negotiation (spec 05 amendment 2026-10-28): the
        # API server accepts only json/yaml/protobuf for pods/log requests;
        # anything else (e.g. a bare text/plain) earns a 406 NotAcceptable.
        # Guards against the bug that shipped when the app asked for
        # Accept: text/plain and 406'd on every real cluster.
        accept = self.headers.get("Accept", "")
        if accept and not any(m in accept for m in (
                "*/*", "application/json", "application/yaml",
                "application/vnd.kubernetes.protobuf")):
            return 406, (b'{"kind":"Status","apiVersion":"v1","status":"Failure",'
                        b'"message":"only the following media types are accepted: '
                        b'application/json, application/yaml, '
                        b'application/vnd.kubernetes.protobuf",'
                        b'"reason":"NotAcceptable","code":406}')
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
                if "web-3" in self.DELETED:
                    return 200, self._pods([])
                return 200, self._pods([self._pod("web-3", phase="Running")])
            self._seed()
            order = ["web-1", "web-2", "web-multi", "web-drop", "web-nolog",
                     "web-protected"]
            items = [json.dumps(self.MUT[n]) for n in order
                     if n not in self.DELETED]
            return 200, self._pods(items, cont="PAGE2")
        if base.startswith("/api/v1/namespaces/web/pods/"):
            if base.endswith("/log"):
                return self._log(base, query)
            name = base.rsplit("/", 1)[-1]
            self._seed()
            if name in self.MUT:
                if name in self.DELETED:
                    return 404, json.dumps(
                        {"kind": "Status", "reason": "NotFound",
                         "code": 404}).encode()
                return 200, json.dumps(self.MUT[name]).encode()
            if name == "web-multi":
                return 200, self._pod_multi(name).encode()
            return 200, self._pod(name).encode()
        # ---- spec 07: CRD discovery + CR routes ---------------------------
        if base == "/apis/apiextensions.k8s.io/v1/customresourcedefinitions":
            if self.CRD_403[0]:
                return 403, b'{"message":"customresourcedefinitions is forbidden","code":403}'
            return 200, self._pods([json.dumps(c) for c in self._crds()])
        if ("widgets" in base
                and (base.startswith("/apis/example.com/v1/")
                     or base.startswith("/apis/example.com/v2/"))):
            self._seed_widgets()
            if "/widgets/" in base:  # single item by name
                name = base.rsplit("/", 1)[-1]
                if name in self.WIDGET_MUT:
                    return 200, json.dumps(self.WIDGET_MUT[name]).encode()
                return 404, json.dumps({"kind": "Status", "reason": "NotFound",
                                        "code": 404}).encode()
            return 200, self._pods(
                [json.dumps(self.WIDGET_MUT[n]) for n in self.WIDGET_NAMES])
        if base.startswith("/apis/stable.example.com/v1/clusters"):
            item = {
                "apiVersion": "stable.example.com/v1", "kind": "Cluster",
                "metadata": {"name": "big", "creationTimestamp": "2026-03-01T00:00:00Z"},
                "spec": {"location": "us-east", "nodes": 3},
                "status": {"phase": "Bound"},
            }
            if base.endswith("/clusters"):
                return 200, self._pods([json.dumps(item)])
            return 200, json.dumps(item).encode()
        return None

    def do_GET(self):
        auth = self.headers.get("Authorization", "<none>")
        log(f"GET {self.path} Auth={auth}")
        if REQUIRE_TOKEN and self.path != "/version" \
                and not self.path.startswith("/__test__/") \
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
        elif self.path.startswith("/__test__/"):
            # driver-side observation endpoints (no auth; never called by app)
            self._seed()
            parts = self.path.split("/")  # ['', '__test__', verb, name]
            verb, name = parts[2], parts[3] if len(parts) > 3 else ""
            if verb == "stats":
                self._seed_widgets()
                if name in self.WIDGET_MUT:
                    return self._json(200, {
                        "puts": self.WIDGET_PUTS.get(name, 0),
                        "rv": self.WIDGET_RV.get(name),
                        "deleted": False})
                return self._json(200, {
                    "puts": self.PUTS.get(name, 0),
                    "rv": self.RV.get(name),
                    "deleted": name in self.DELETED})
            if verb == "crd403":  # spec 07: toggle CRD-list Forbidden
                self.CRD_403[0] = name == "on"
                return self._json(200, {"crd403": self.CRD_403[0]})
            if verb == "bump":  # simulate an out-of-band cluster edit (409)
                if name in self.RV:
                    self.RV[name] += 1
                    self.MUT[name]["metadata"]["resourceVersion"] = str(self.RV[name])
                return self._json(200, {"rv": self.RV.get(name)})
            return self._json(404, {"error": "unknown test route"})
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
