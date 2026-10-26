#!/usr/bin/env python3
"""Spec 03 emulator acceptance driver (devbox only).

Boots the API 37 headless emulator, runs a TLS API stub with the throwaway
CA, then drives the app over adb to exercise the acceptance criteria that
unit tests cannot cover end-to-end:

  * paste-import token kubeconfig (CA-pinned TLS) -> probe hits the stub
    with `Authorization: Bearer <token>` (spec 03 "green dot")
  * kill + relaunch -> connection restored from Keystore/DataStore and a
    re-probe (refresh) reaches the stub again
  * delete -> row gone, blob dir empty
  * exec-plugin kubeconfig -> specific error, nothing persisted, no traffic

Prints STEP lines and a final ACCEPTANCE_PASS / ACCEPTANCE_FAIL.
"""
import base64
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ANDROID_HOME = os.path.expanduser("~/Android/Sdk")
ADB = f"{ANDROID_HOME}/platform-tools/adb"
EMULATOR = f"{ANDROID_HOME}/emulator/emulator"
SDKMANAGER = f"{ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER = f"{ANDROID_HOME}/cmdline-tools/latest/bin/avdmanager"
APK = os.path.expanduser("~/kubedroid/build/outputs/apk/debug/kubedroid-debug.apk")
HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.join(HERE, "accept-server.py")

WORK = os.path.expanduser("~/kubedroid-accept")
LOG = "/tmp/kubedroid-accept-server.log"
PORT = 6443
PKG = "com.neozeon.kubedroid.debug"
ACTIVITY = "com.neozeon.kubedroid.MainActivity"
# Value must avoid dictionary-word+hyphen combos (e.g. "acceptance-test"):
# the emulator's LatinIME hardware-key composing region deterministically
# drops such runs before Enter commits them (probe_typing3.py evidence).
# disable_ime() clears the secure IME settings before typing (pm-disable of
# LatinIME fails silently); `input text` chunks are additionally verified
# line-by-line and the field is wiped+retyped on a corrupt retry.
TOKEN = "0f3a9c7e2b8d41e6"
# The app reaches the stub via guest loopback + `adb reverse tcp:PORT`
# (re-established after every emulator boot). Do NOT switch this back to
# 10.0.2.2: on the API-37 emulator the slirp path silently drops SYNs from
# app UIDs (verified: shell UID connects, app UID times out with zero
# packets leaving the guest, stub provably alive — matrix_net.py evidence).
# The server cert SANs already include IP:127.0.0.1.
SERVER_URL = f"https://127.0.0.1:{PORT}"

PROFILES = {
    "37": ("kv37", "emulator-5558", "system-images;android-37.0;default;x86_64"),
    "26": ("kv26", "emulator-5556", "system-images;android-26;google_apis;x86_64"),
}
API = sys.argv[1] if len(sys.argv) > 1 else "37"
AVD, SERIAL, IMAGE = PROFILES[API]

failures = []


def step(name, ok, detail=""):
    print(f"STEP {'PASS' if ok else 'FAIL'} {name} {detail}", flush=True)
    if not ok:
        failures.append(name)
    return ok


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def adb(*args, **kw):
    return sh([ADB, "-s", SERIAL, *args], **kw)


# ---------------------------------------------------------------- fixtures
def make_certs():
    os.makedirs(WORK, exist_ok=True)
    ca_key, ca_crt = f"{WORK}/ca-key.pem", f"{WORK}/ca-cert.pem"
    srv_key, srv_crt = f"{WORK}/server-key.pem", f"{WORK}/server-cert.pem"
    if not os.path.exists(srv_crt):
        # EC certs keep the base64 CA blob small — the uiautomator
        # accessibility text caps around ~1.8k chars and a 2048-bit RSA
        # cert blows past it, hiding the tail of the typed kubeconfig.
        sh(["openssl", "ecparam", "-name", "prime256v1", "-genkey",
            "-noout", "-out", ca_key])
        sh(["openssl", "req", "-x509", "-new", "-key", ca_key, "-days", "2",
            "-out", ca_crt, "-subj", "/CN=kubedroid-accept-ca"])
        sh(["openssl", "ecparam", "-name", "prime256v1", "-genkey",
            "-noout", "-out", srv_key])
        sh(["openssl", "req", "-new", "-key", srv_key,
            "-out", f"{WORK}/server.csr", "-subj", "/CN=10.0.2.2"])
        with open(f"{WORK}/san.ext", "w") as f:
            f.write("subjectAltName=IP:10.0.2.2,IP:127.0.0.1\n")
        sh(["openssl", "x509", "-req", "-in", f"{WORK}/server.csr",
            "-CA", ca_crt, "-CAkey", ca_key, "-CAcreateserial",
            "-out", srv_crt, "-days", "2", "-extfile", f"{WORK}/san.ext"])
    b64 = base64.b64encode(open(ca_crt, "rb").read()).decode()
    return ca_key, ca_crt, srv_key, srv_crt, b64


def kubeconfig(ca_b64, with_exec=False):
    user = (
        "users:\n"
        "- name: u1\n"
        "  user:\n"
        "    exec:\n"
        "      command: kubelogin\n"
        "      args:\n"
        "      - login\n"
    ) if with_exec else (
        "users:\n"
        "- name: u1\n"
        "  user:\n"
        f"    token: {TOKEN}\n"
    )
    return (
        "apiVersion: v1\n"
        "kind: Config\n"
        "current-context: accept-east\n"
        "clusters:\n"
        "- name: c1\n"
        "  cluster:\n"
        f"    server: {SERVER_URL}\n"
        f"    certificate-authority-data: {ca_b64}\n"
        "contexts:\n"
        "- name: accept-east\n"
        "  context:\n"
        "    cluster: c1\n"
        "    user: u1\n"
        + user
    )


def type_text(text):
    """input text via adb; spaces must arrive as %s (device-shell safe).

    Small 20-char chunks: the API-26 emulator's input dispatcher drops
    characters from large injections even with no IME bound (observed
    'contexts:'→'content:'); small synchronous chunks land reliably.
    (Note: `input` parses options only BEFORE the event command — an
    `-d` written after `text` becomes literal typed text.)
    """
    for i in range(0, len(text), 20):
        adb("shell", "input", "text", text[i:i + 20].replace(" ", "%s"))
        time.sleep(0.08)


def find_edit_nodes(r):
    return [
        n for n in r.iter("node")
        if (n.get("class") or n.get("className") or "").endswith("EditText")
        or (n.get("text") or "").startswith("kubeconfig (YAML)")
    ]


def disable_ime():
    """No soft keyboard: hardware keyevents go straight to the focused
    view. LatinIME's composing region drops/corrupts long technical runs
    (API 37 drops 'acceptance-test-token' style values; API 26 turns
    'contexts:' into 't:'). Writing the string 'null' into
    default_input_method does NOT disable the IME — the system falls back
    to the default — so `ime disable` the real component and DELETE the
    settings keys, then verify the setting is truly absent."""
    ime = adb("shell", "settings", "get", "secure",
              "default_input_method").stdout.strip()
    if "/" in ime:
        adb("shell", "ime", "disable", ime)
    # also the plain-AOSP component (image flavor dependent)
    adb("shell", "ime", "disable", "com.android.inputmethod.latin/.LatinIME")
    adb("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard",
        "0")
    for _ in range(3):
        r = adb("shell", "ime", "list", "-s")
        ids = [ln.strip() for ln in (r.stdout or "").splitlines() if "/" in ln]
        if not ids:
            break
        for ime_id in ids:
            adb("shell", "ime", "disable", ime_id)
    # `ime disable` is NOT enough on the API-26 google_apis image: IMMS
    # still binds the system-default IME and its composing/autocorrect
    # region rewrites injected text on Enter-commit (observed
    # 'kind:'→'kindly:', 'https://'→'have://'). Package-disable the IME
    # packages themselves — IMMS cannot bind a disabled package.
    r = adb("shell", "ime", "list", "-a", "-s")
    pkgs = {ln.split("/")[0] for ln in (r.stdout or "").splitlines() if "/"}
    pkgs |= {"com.google.android.inputmethod.latin",
             "com.android.inputmethod.latin",
             "com.google.android.googlequicksearchbox"}
    for pkg in sorted(pkgs):
        adb("shell", "pm", "disable-user", "--user", "0", pkg)
    for key in ("default_input_method", "enabled_input_methods"):
        adb("shell", "settings", "delete", "secure", key)
    r = adb("shell", "ime", "list", "-s")
    remaining = [ln.strip() for ln in (r.stdout or "").splitlines() if "/" in ln]
    # On API 26 the IME resolver repopulates settings with fallbacks even
    # for disabled components — an empty enabled list is the real proof.
    step("ime-disabled", not remaining, f"(remaining={remaining!r})")


def _clear_field(probe):
    """Wipe the focused field (KEYCODE_CLEAR, else batched DELs)."""
    adb("shell", "input", "keyevent", "28")  # KEYCODE_CLEAR
    time.sleep(0.3)
    c = probe()
    if c in ("", None):
        return
    adb("shell", "input", "keyevent", "123")  # MOVE_END
    n = len(c) + 3
    while n > 0:
        adb("shell", "input", "keyevent", *["67"] * min(n, 150))
        n -= 150
    time.sleep(0.5)


def type_multiline(verify_probe, text):
    """Type YAML line-by-line; after each line verify it landed, retry once.

    adb `input text` is fire-and-forget and occasionally drops commands,
    so every line is verified against the live UI (verify_probe returns
    the current focused-field text). A retry WIPEs and retypes everything
    so far: re-typing only the failed line leaves IME-corrupt garbage
    (e.g. 'I:') in the YAML, which silently breaks parsing.
    """
    lines = text.split("\n")
    for i, line in enumerate(lines):
        for attempt in (1, 2, 3):
            if attempt >= 2:
                print(f"  retry line (attempt {attempt}): {line!r}", flush=True)
                _clear_field(verify_probe)
                for prev in lines[:i]:
                    if prev:
                        type_text(prev)
                    adb("shell", "input", "keyevent", "66")
                    time.sleep(0.5)
            if line:
                type_text(line)
            adb("shell", "input", "keyevent", "66")  # Enter
            time.sleep(1.0)
            content = verify_probe()
            # uiautomator truncates node text near ~1.8k chars; beyond that
            # the tail is invisible and a retry would duplicate content.
            # None = dump flaked (indeterminate): trust the type, never retype.
            if (not line or content is None or len(content) >= 1700
                    or line.strip() in content):
                break

    # Final whole-content pass: per-line retries give up silently, and on
    # API-26 dropped lines left the parser rejecting a "complete" config
    # (Import stayed disabled with zero dumps lying). Verify every line is
    # present; if any are missing, wipe and retype the entire text once.
    expected = [l.strip() for l in lines if l.strip()]
    def strict_probe():
        # like verify_probe but refuses to flake away the completeness check
        for _ in range(3):
            try:
                r = ui_dump(retries=8)
            except RuntimeError:
                time.sleep(1)
                continue
            for n in r.iter("node"):
                if (n.get("class") or n.get("className") or "").endswith("EditText"):
                    return n.get("text") or ""
        return None

    for final_pass in (1, 2, 3):
        content = strict_probe()
        if content is None or len(content) >= 1700:
            break  # indeterminate or tail-truncated: trust per-line work
        missing = [l for l in expected if l not in content]
        if not missing:
            break
        ctx = []
        for l in missing[:3]:
            i = content.find(l[:6])
            if i < 0:
                i = content.find(l[:4])
            if i >= 0:
                ctx.append(content[max(0, i - 40):i + 60])
        print(f"  FINAL PASS {final_pass}: missing {missing!r} "
              f"near={ctx!r}", flush=True)
        _clear_field(verify_probe)
        for l in lines:
            if l:
                type_text(l)
            adb("shell", "input", "keyevent", "66")
            time.sleep(0.8)


def ui_dump(retries=6):
    """Fresh uiautomator tree.

    `uiautomator dump` silently fails (window not idle) and leaves the
    previous /sdcard/ui.xml in place — reading it would return a stale
    tree. Delete the target first and retry until a real dump lands.
    """
    last = "unknown"
    for _ in range(retries):
        adb("shell", "rm", "-f", "/sdcard/ui.xml")
        r = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        blob = (r.stdout or "") + (r.stderr or "")
        if "ERROR" in blob or "could not get idle" in blob:
            last = blob.strip()[:80]
            time.sleep(0.7)
            continue
        out = adb("shell", "cat", "/sdcard/ui.xml").stdout
        if "<hierarchy" not in out:
            last = "empty dump"
            time.sleep(0.7)
            continue
        try:
            return ET.fromstring(
                out[out.index("<hierarchy"):out.rindex("</hierarchy>") + 12])
        except ET.ParseError as exc:
            last = str(exc)[:80]
            time.sleep(0.7)
    raise RuntimeError(f"uiautomator dump failed after {retries}: {last}")


def ui_dump_tree():
    """ui_dump that degrades to an empty tree instead of raising.

    Call sites already cope with no matches (tap_where/find_nodes return
    nothing useful), which keeps flaky-dump retries from crashing the run.
    """
    try:
        return ui_dump()
    except RuntimeError:
        return ET.fromstring("<hierarchy/>")


def find_nodes(root, **attrs):
    # text comparisons use strip(): uiautomator on some API levels reports
    # node text with surrounding whitespace, silently breaking exact match
    def match(n, k, v):
        got = n.get(k)
        return (got or "").strip() == v if k == "text" else got == v
    return [n for n in root.iter("node")
            if all(match(n, k, v) for k, v in attrs.items())]


def center(node):
    b = node.get("bounds")  # [x1,y1][x2,y2]
    import re
    x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", b))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_where(root, **attrs):
    nodes = find_nodes(root, **attrs)
    if not nodes:
        return False
    x, y = center(nodes[0])
    adb("shell", "input", "tap", str(x), str(y))
    return True


def wait_node(**attrs):
    deadline = time.time() + 20
    while time.time() < deadline:
        try:
            root = ui_dump_tree()
            nodes = find_nodes(root, **attrs)
            if nodes:
                return root, nodes[0]
        except Exception:
            pass
        time.sleep(1)
    return None, None


def has_text(text, timeout=20, substring=False):
    """Poll for a node whose text equals (or, with substring=True,
    contains) `text`. Dump flakes degrade to a retry, never a false fail."""
    deadline = time.time() + timeout
    while True:
        try:
            r = ui_dump(retries=3)
            for n in r.iter("node"):
                t = (n.get("text") or "").strip()
                if (text in t if substring else t == text):
                    return True
        except RuntimeError:
            pass
        if time.time() >= deadline:
            return False
        time.sleep(0.8)


def log_count(pattern):
    try:
        with open(LOG) as f:
            return sum(1 for line in f if pattern in line)
    except FileNotFoundError:
        return 0


def log_lines(pattern):
    try:
        with open(LOG) as f:
            return [line for line in f if pattern in line]
    except FileNotFoundError:
        return []


def _dump_debug(root):
    try:
        if root is None:
            return "(no dump)"
        texts = [(n.get("text") or "").strip() for n in root.iter("node")]
        texts = [t for t in texts if t][:40]
        with open("/tmp/accept-dump.xml", "w") as f:
            ET.ElementTree(root).write(f, encoding="unicode")
        return f"nodes={texts}"
    except Exception as e:  # diagnostics must never mask the real failure
        return f"(debug failed: {e})"


def logcat_errors():
    """Recent fatal/exception lines — self-diagnosis for save-path failures."""
    out, other = [], []
    for buf in ("crash", "main"):
        r = adb("shell", "logcat", "-d", "-b", buf, "-v", "brief")
        for line in (r.stdout or "").splitlines():
            s = line.strip()
            if ("uiautomator" in s or "RuntimeInit" in s
                    or "memtrack" in s or "SurfaceFlinger" in s
                    or "WorkerRegistryImpl" in s
                    or "LatinMotionEventHandler" in s):
                continue  # driver/platform churn is noise
            if "kubedroid" in s or "AndroidRuntime" in s or "FATAL" in s:
                out.append(s[:200])
            elif "E/" in s:
                other.append(s[:200])
    picked = out or other  # app-relevant lines win; else generic errors
    return " | ".join(picked[-15:])[:1500]


ENV = None
SERVER_PROC = None


def start_stub():
    """(Re)launch the TLS stub; returns the Popen handle. LOG survives restarts."""
    global SERVER_PROC
    SERVER_PROC = subprocess.Popen(
        [sys.executable, SERVER, str(PORT), LOG,
         f"{WORK}/server-cert.pem", f"{WORK}/server-key.pem", TOKEN],
        env=ENV,
    )
    deadline = time.time() + 10
    while time.time() < deadline and log_count("SERVER_START") == 0:
        time.sleep(0.3)
    return SERVER_PROC


def stub_get(path):
    """Driver-side observation call to the stub's /__test__ endpoints
    (spec 06: PUT counts, server RV, deleted flags, out-of-band bump)."""
    import json as _json
    import ssl as _ssl
    import urllib.request
    ctx = _ssl._create_unverified_context()
    req = urllib.request.Request(f"https://127.0.0.1:{PORT}{path}")
    with urllib.request.urlopen(req, context=ctx, timeout=5) as resp:
        return _json.loads(resp.read().decode())


def main():
    global ENV
    ENV = dict(os.environ, ANDROID_HOME=ANDROID_HOME, ANDROID_SDK_ROOT=ANDROID_HOME)
    env = ENV

    _, _, _, _, ca_b64 = make_certs()
    step("fixtures", True)

    # TLS stub
    if os.path.exists(LOG):
        os.remove(LOG)
    server = start_stub()
    if not step("stub-server-started", log_count("SERVER_START") == 1):
        server.terminate()
        return finish()

    # emulator
    sh([SDKMANAGER, IMAGE], env=env)
    subprocess.run([AVDMANAGER, "delete", "avd", "-n", AVD],
                   env=env, capture_output=True, text=True, input="no\n")
    created = subprocess.run(
        [AVDMANAGER, "create", "avd", "-n", AVD, "-k", IMAGE, "-d", "pixel"],
        env=env, capture_output=True, text=True, input="no\n",
    )
    if not step("avd-create", created.returncode == 0,
                (created.stdout.strip().splitlines() or [""])[-1]
                or created.stderr[-200:]):
        server.terminate()
        return finish()
    emu = subprocess.Popen(
        [EMULATOR, "-avd", AVD, "-port", SERIAL.split("-")[1], "-no-window", "-no-audio",
         "-no-boot-anim", "-gpu", "swiftshader_indirect", "-no-snapshot",
         "-wipe-data"],
        env=env, stdout=open(f"/tmp/emu-accept-{API}.log", "w"), stderr=subprocess.STDOUT,
    )
    try:
        run_app_flow(ca_b64)
    finally:
        adb("emu", "kill")
        emu.terminate()
        if SERVER_PROC is not None:
            SERVER_PROC.terminate()
    return finish()


def run_app_flow(ca_b64):
    try:
        sh([ADB, "-s", SERIAL, "wait-for-device"], timeout=240)
    except subprocess.TimeoutExpired:
        step("emulator-boot", False, "(wait-for-device timeout)")
        return
    deadline = time.time() + 300
    while time.time() < deadline:
        if adb("shell", "getprop", "sys.boot_completed").stdout.strip() == "1":
            break
        time.sleep(3)
    step("emulator-boot", adb("shell", "getprop", "sys.boot_completed").stdout.strip() == "1")

    # host stub -> guest loopback (see SERVER_URL comment); survives pm
    # clear and force-stop, but is lost on emulator reboot, so set it here.
    adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    step("adb-reverse", f"tcp:{PORT}" in adb("reverse", "--list").stdout)

    disable_ime()

    adb("install", "-r", APK)
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "pm", "clear", PKG)  # pristine state incl. keystore+datastore
    adb("shell", "am", "start", "-n", f"{PKG}/{ACTIVITY}")
    time.sleep(8)

    root, node = wait_node(text="Paste kubeconfig")
    if not step("empty-state", node is not None):
        return

    # ---- paste import (token, CA-pinned TLS) ----
    root, _ = wait_node(text="Paste kubeconfig")  # re-dump for fresh tree
    tap_where(root, text="Paste kubeconfig") if root is not None else None
    def field_text():
        try:
            r = ui_dump(retries=2)
        except RuntimeError:
            return None  # dump flaked — treat as indeterminate, don't retype
        for n in r.iter("node"):
            if (n.get("class") or n.get("className") or "").endswith("EditText"):
                return n.get("text") or ""
        return None

    # retry: tap may race the empty-state render; poll for the dialog edit
    edits = []
    deadline = time.time() + 25
    while time.time() < deadline and not edits:
        root = ui_dump_tree()
        edits = find_edit_nodes(root)
        if not edits:
            tap_where(root, text="Paste kubeconfig")
            time.sleep(2)
    if not step("paste-dialog", bool(edits), _dump_debug(root) if not edits else ""):
        return
    x, y = center(edits[0])
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1.5)
    type_multiline(field_text, kubeconfig(ca_b64))
    time.sleep(2)

    # Did the text actually land in the field? (focus may have been missed)
    typed = False
    deadline = time.time() + 15
    while time.time() < deadline and not typed:
        try:
            root = ui_dump(retries=3)
            typed = any("apiVersion" in (n.get("text") or "")
                        for n in root.iter("node"))
        except RuntimeError:
            time.sleep(1)
    if not typed:
        step("text-registered-first-try", False, "(field empty — refocusing)")
        root = ui_dump()  # fresh tree: don't reuse stale edit bounds
        edits = find_edit_nodes(root)
        if edits:
            x, y = center(edits[0])
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(1.5)
            type_multiline(field_text, kubeconfig(ca_b64))
            time.sleep(2)

    # Compose exposes the click action on the merged parent, not the Text
    # node — match by text only, like the Paste kubeconfig tap above.
    # enabled must be checked for real: a parse error leaves the button
    # present-but-disabled, and tapping it is a silent no-op (API-26
    # 'contexts:'→'t:' IME corruption masqueraded as a tap failure).
    # Compose puts enabled=false on the merged clickable ANCESTOR, not the
    # Text child — walk parents via a child->parent map.
    def import_button_enabled(r):
        parent = {c: p for p in r.iter("node") for c in p}
        diag = ""
        for n in r.iter("node"):
            # compare STRIPPED: API-26 uiautomator reports Compose Text
            # with trailing whitespace, exact match never hits
            if (n.get("text") or "").strip() != "Import cluster":
                continue
            a, ok, chain = n, True, []
            while a is not None:
                chain.append(f"{a.get('class','?').split('.')[-1]}"
                             f"[click={a.get('clickable')},"
                             f"en={a.get('enabled')}]")
                if a.get("clickable") == "true" and a.get("enabled") == "false":
                    ok = False
                    break
                a = parent.get(a)
            if ok:
                return n, ""
            diag = " <- ".join(chain)
        return None, diag

    root, confirm = None, None
    last_diag, good_dumps = "", 0
    deadline = time.time() + 20
    while time.time() < deadline and confirm is None:
        r = ui_dump_tree()
        if len(list(r.iter("node"))) > 1:
            good_dumps += 1
        n, last_diag = import_button_enabled(r)
        if n is not None:
            root, confirm = r, n
        else:
            time.sleep(1)
    if confirm is None and root is None:
        root = ui_dump_tree()
    if not step("import-enabled", confirm is not None,
                (f"(good_dumps={good_dumps}, "
                 f"field_tail={(field_text() or '')[-160:]!r}) "
                 + (last_diag or _dump_debug(root)))
                if confirm is None else ""):
        return
    # Tap Import with confirmation: on slow devices the single blind tap
    # can miss (dialog stays open); the tall field can also push the button
    # toward the dialog fold. Re-dump, swipe to reveal, re-tap until closed.
    for _ in range(3):
        root = ui_dump_tree()
        nodes = find_nodes(root, text="Import cluster")
        if not nodes:
            ed = find_edit_nodes(root)
            if ed:
                ex, ey = center(ed[0])
                adb("shell", "input", "swipe", str(ex), str(ey + 300),
                    str(ex), str(ey - 300), "400")
            time.sleep(1)
            continue
        tx, ty = center(nodes[0])
        adb("shell", "input", "tap", str(tx), str(ty))
        time.sleep(2.5)
        chk = ui_dump_tree()
        if not any("Import cluster" in (n.get("text") or "")
                   for n in chk.iter("node")):
            break  # dialog closed — tap landed

    deadline = time.time() + 30
    while time.time() < deadline and log_count(f"GET /version Auth=Bearer {TOKEN}") == 0:
        time.sleep(1)
    step("probe-hit-stub-with-token",
         log_count(f"GET /version Auth=Bearer {TOKEN}") >= 1,
         "" if log_count(f"GET /version Auth=Bearer {TOKEN}") else logcat_errors())

    root, row = wait_node(text="accept-east")
    if row is not None:
        step("row-visible", True)
    else:
        # is the dialog still open? (Import tap missed / button disabled)
        stuck = ui_dump_tree()
        still_open = any("Import cluster" in (n.get("text") or "")
                         for n in stuck.iter("node"))
        step("row-visible", False,
             f"(dialog still open={still_open}) {_dump_debug(stuck)} "
             f"{logcat_errors()}")

    # ---- kill + relaunch: restored and re-probe ----
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/{ACTIVITY}")
    root, row = wait_node(text="accept-east")
    if row is None:
        # am start can be swallowed by the dying previous process — retry
        adb("shell", "am", "start", "-n", f"{PKG}/{ACTIVITY}")
        root, row = wait_node(text="accept-east")
    if row is None:
        time.sleep(3)  # allow a slow start/crash to fully land
        top = adb("shell", "dumpsys", "activity", "activities")
        resumed = [ln.strip()[:120] for ln in (top.stdout or "").splitlines()
                   if "ResumedActivity" in ln][:2]
        step("restored-after-restart", False,
             f"(top={resumed!r}) {_dump_debug(ui_dump_tree())} "
             f"{logcat_errors()}")
    else:
        step("restored-after-restart", True)

    root = ui_dump_tree()
    before = log_count("GET /version")
    # uiautomator attribute is content-desc (NOT contentDesc) — a mismatch
    # here silently matches nothing and the tap is a no-op.
    tap_where(root, **{"content-desc": "Refresh"})
    deadline = time.time() + 30
    while time.time() < deadline and log_count("GET /version") <= before:
        time.sleep(1)
    step("reprobe-after-restart", log_count("GET /version") > before,
         "(Keystore decrypt + ApiClient rebuild OK)")

    # ---- spec 04: row tap browses; grid, list, infinite scroll, detail ----
    root, row = wait_node(text="accept-east")
    if row is not None:
        x, y = center(row)
        adb("shell", "input", "tap", str(x), str(y))
    grid_root, pods_card = wait_node(text="Pods")
    if not step("spec04-browse-grid", pods_card is not None,
                "" if pods_card is not None else _dump_debug(ui_dump_tree())):
        return

    # tap the Pods card -> resource list backed by the canned stub
    if pods_card is not None:
        x, y = center(pods_card)
        adb("shell", "input", "tap", str(x), str(y))
    _, web1 = wait_node(text="web-1")
    if not step("spec04-list-rows", web1 is not None,
                "" if web1 is not None else _dump_debug(ui_dump_tree())):
        return

    # infinite scroll: swipe near the last row to trigger the continue fetch
    if web1 is not None:
        x, y = center(web1)
        adb("shell", "input", "swipe", str(x), str(y + 60), str(x), str(y - 500), "300")
    _, web3 = wait_node(text="web-3")
    step("spec04-infinite-scroll", web3 is not None and
         log_count("continue=PAGE2") >= 1,
         f"(continue_hits={log_count('continue=PAGE2')}) "
         "" if web3 is not None else _dump_debug(ui_dump_tree()))

    # tap web-1 -> Summary shows status facts; YAML tab renders the doc
    _, web1 = wait_node(text="web-1")
    if web1 is not None:
        x, y = center(web1)
        adb("shell", "input", "tap", str(x), str(y))
    _, ip = wait_node(text="10.42.0.7")
    if not step("spec04-detail-summary", ip is not None,
                "" if ip is not None else _dump_debug(ui_dump_tree())):
        return
    root = ui_dump_tree()
    tap_where(root, text="YAML")
    shown = False
    deadline = time.time() + 15
    while time.time() < deadline and not shown:
        try:
            r = ui_dump(retries=3)
            shown = any("podIP" in (n.get("text") or "")
                        for n in r.iter("node"))
        except RuntimeError:
            time.sleep(1)
    step("spec04-detail-yaml", shown)

    # ---- spec 05: pod log viewer ------------------------------------------
    # web-1 detail (YAML tab is fine — Logs lives in the top bar)
    root = ui_dump_tree()
    tap_where(root, text="Logs")
    opened = has_text("kblog-beta", 25)
    tail_req = [l for l in log_lines("pods/web-1/log") if "tailLines=1000" in l]
    step("spec05-open-logs",
         opened and bool(tail_req) and "timestamps=true" in tail_req[0],
         f"(tail_reqs={len(tail_req)})" if opened
         else _dump_debug(ui_dump_tree()) + " " + logcat_errors())

    # static tail: all three canned lines render (timestamps hidden by default)
    static_ok = all(has_text(t, 5) for t in
                    ("kblog-alpha", "kblog-beta", "kblog-gamma"))
    step("spec05-log-static", static_ok,
         "" if static_ok else _dump_debug(ui_dump_tree()))

    # Timestamps: hidden by default; toggle reveals prefix; ZERO new requests
    ts_hidden = not has_text("2026-01-01T00:00:02Z kblog-beta", 3)
    reqs_before = len(log_lines("pods/web-1/log"))
    root = ui_dump_tree()
    tap_where(root, text="TS")
    shown_ts = has_text("2026-01-01T00:00:02Z kblog-beta", 10)
    reqs_after = len(log_lines("pods/web-1/log"))
    step("spec05-timestamps-display-only",
         ts_hidden and shown_ts and reqs_after == reqs_before,
         f"(hidden={ts_hidden} shown={shown_ts} reqs {reqs_before}->{reqs_after})")

    # Follow: server streams a new line every second; assert one arrives live
    root = ui_dump_tree()
    tap_where(root, **{"content-desc": "Follow"})
    # Timestamps are ON from the earlier step: match as substring of the
    # prefixed row text ("2026-...Z kblog-follow-N").
    got_follow = (has_text("kblog-follow-1", 25, substring=True)
                  and has_text("kblog-follow-2", 5, substring=True))
    follow_req = [l for l in log_lines("pods/web-1/log") if "follow=true" in l]
    step("spec05-log-follow-append", got_follow and bool(follow_req),
         f"(follow_reqs={len(follow_req)})" if got_follow
         else _dump_debug(ui_dump_tree()))

    # Save: SAF picker opens; cancel leaves no file request (no tailLines)
    root = ui_dump_tree()
    tap_where(root, text="Save")
    picker = False
    deadline = time.time() + 15
    while time.time() < deadline and not picker:
        top = adb("shell", "dumpsys", "activity", "activities")
        # resumed-only detection: API-26 keeps documentsui in the activity
        # history forever, which made a plain substring test pass before the
        # picker was actually up and desync every later back-navigation
        resumed = [l for l in (top.stdout or "").splitlines()
                   if "ResumedActivity" in l]
        picker = any("com.android.documentsui" in l for l in resumed)
        if not picker:
            time.sleep(1)
    adb("shell", "input", "keyevent", "4")  # cancel the picker
    # poll until the RESUMED activity is no longer the picker: API-26 keeps
    # documentsui in the activity *history* after dismissal, so a plain
    # "documentsui in dumpsys" test would stall the full timeout (and every
    # later navigation raced it).
    deadline = time.time() + 30
    while time.time() < deadline:
        top = adb("shell", "dumpsys", "activity", "activities")
        resumed = [l for l in (top.stdout or "").splitlines()
                   if "ResumedActivity" in l]
        if not any("documentsui" in l for l in resumed):
            break
        time.sleep(1)
    time.sleep(2)
    back_on_logs = (has_text("kblog-tail-1", 10, substring=True)
                    or has_text("kblog-beta", 5, substring=True))
    save_reqs = [l for l in log_lines("pods/web-1/log")
                 if "tailLines" not in l.split("Auth=")[0]]
    step("spec05-log-save-picker", picker and back_on_logs and not save_reqs,
         f"(picker={picker} logs={back_on_logs} save_reqs={len(save_reqs)})")

    # container dropdown on the two-container pod
    for _ in range(2):  # logs -> detail -> list
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)
    _, multi_row = wait_node(text="web-multi")
    if multi_row is not None:
        x, y = center(multi_row)
        adb("shell", "input", "tap", str(x), str(y))
    time.sleep(2)
    root = ui_dump_tree()
    tap_where(root, text="Logs")
    dropdown = has_text("containers", 15)
    root = ui_dump_tree()
    tap_where(root, text="containers")
    time.sleep(1)
    root = ui_dump_tree()
    tap_where(root, text="sidecar")
    sidecar_ok = has_text("kblog-sidecar-only", 20)
    sidecar_req = [l for l in log_lines("pods/web-multi/log")
                   if "container=sidecar" in l]
    step("spec05-log-container-switch", dropdown and sidecar_ok and bool(sidecar_req),
         f"(dropdown={dropdown} reqs={len(sidecar_req)})" if sidecar_ok
         else _dump_debug(ui_dump_tree()))

    # mid-stream drop on web-drop: lines, then error card, then Retry recovers
    for _ in range(2):  # logs -> detail -> list
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)
    _, drop_row = wait_node(text="web-drop")
    if drop_row is not None:
        x, y = center(drop_row)
        adb("shell", "input", "tap", str(x), str(y))
    time.sleep(2)
    root = ui_dump_tree()
    tap_where(root, text="Logs")
    saw_drop_lines = has_text("kblog-drop-1", 20)
    err_card = has_text("Could not reach the cluster", 20, substring=True)
    drop_retry = False
    if err_card:
        root = ui_dump_tree()
        tap_where(root, text="Retry")
        drop_retry = has_text("kblog-drop-3", 25)
    step("spec05-log-disconnect", saw_drop_lines and err_card and drop_retry,
         "" if (saw_drop_lines and err_card and drop_retry)
         else f"(lines={saw_drop_lines} card={err_card} retry={drop_retry}) "
              + _dump_debug(ui_dump_tree()))

    # 403 pods/log on web-nolog: card names the separate RBAC grant
    for _ in range(2):  # logs -> detail -> list
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)
    _, nolog_row = wait_node(text="web-nolog")
    if nolog_row is not None:
        x, y = center(nolog_row)
        adb("shell", "input", "tap", str(x), str(y))
    time.sleep(2)
    root = ui_dump_tree()
    tap_where(root, text="Logs")
    forbidden = has_text("pods/log", 20, substring=True)
    step("spec05-log-403", forbidden,
         "" if forbidden else _dump_debug(ui_dump_tree()))

    # ---- spec 06: YAML edit (PUT replace) + delete flows ------------------
    # (runs BEFORE the network-down steps: the stub restart would reset its
    # mutable store, and the secrets flow leaves the pod list behind)
    import json as _json  # noqa: F401 — parity with stub_get internals

    def editor_probe():
        try:
            r = ui_dump(retries=6)
        except RuntimeError:
            return None
        nodes = find_edit_nodes(r)
        return (nodes[0].get("text") or "") if nodes else None

    def tap_editor():
        r = ui_dump_tree()
        nodes = find_edit_nodes(r)
        if nodes:
            x, y = center(nodes[0])
            adb("shell", "input", "tap", str(x), str(y))
            time.sleep(0.6)

    def open_detail(row_text):
        _, row = wait_node(text=row_text)
        if row is not None:
            x, y = center(row)
            adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.5)

    def tap_expect(tap_attrs, expect_attrs, tries=3, settle=5):
        """Tap a control and confirm a node appeared, retrying the tap on a
        fresh dump — blind taps raced list reflows after the deletes.
        expect_attrs matches on text= or content-desc= as per find_nodes."""
        for _ in range(tries):
            root = ui_dump_tree()
            tap_where(root, **tap_attrs)
            deadline = time.time() + settle
            while time.time() < deadline:
                r = ui_dump_tree()
                if find_nodes(r, **expect_attrs):
                    return True
                time.sleep(0.5)
        return False

    def row_overflow_delete(row_name):
        """Open a row's overflow, tap Delete, then confirm the dialog.
        Prints stage diagnostics — the web-protected row resisted the
        same taps that work on other rows, so each stage reports."""
        _, menu_node = wait_node(**{"content-desc": f"Row actions for {row_name}"})
        print(f"  row_overflow[{row_name}] menu node found: {menu_node is not None}",
              flush=True)
        opened = tap_expect({"content-desc": f"Row actions for {row_name}"},
                            {"text": "Delete"})
        print(f"  row_overflow[{row_name}] menu opened: {opened}", flush=True)
        if not opened:
            return False
        confirmed = tap_expect({"text": "Delete"},
                               {"content-desc": "confirm-delete"})
        print(f"  row_overflow[{row_name}] dialog up: {confirmed}", flush=True)
        if not confirmed:
            return False
        root = ui_dump_tree()
        tap_where(root, **{"content-desc": "confirm-delete"})
        return True

    for _ in range(2):  # nolog logs -> detail -> list
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)

    # -- edit web-1: replace the doc in the editor, Save -> PUT 200 -> reload
    open_detail("web-1")
    root = ui_dump_tree()
    tap_where(root, text="YAML")
    time.sleep(0.5)
    root = ui_dump_tree()
    tap_where(root, text="Edit")
    deadline = time.time() + 15
    while time.time() < deadline and editor_probe() is None:
        time.sleep(0.5)
    tap_editor()
    _clear_field(editor_probe)
    NEWDOC = ("apiVersion: v1\nkind: Pod\nmetadata:\n  name: web-1\n"
              "  namespace: web\n  labels:\n    app: frontend\n"
              "spec:\n  containers:\n  - name: c\n    image: nginx:accept06\n")
    type_multiline(editor_probe, NEWDOC)
    root = ui_dump_tree()
    tap_where(root, text="Save")
    saved = has_text("nginx:accept06", 25, substring=True)  # long yaml node
    st = stub_get("/__test__/stats/web-1")
    step("spec06-edit-save", saved and st["puts"] == 1 and str(st["rv"]) == "1001",
         f"(saved={saved} stats={st})" if saved else _dump_debug(ui_dump_tree()))

    # -- conflict on web-2: out-of-band RV bump between open-edit and Save
    adb("shell", "input", "keyevent", "4")  # detail -> list
    time.sleep(1)
    open_detail("web-2")
    root = ui_dump_tree()
    tap_where(root, text="YAML")
    time.sleep(0.5)
    root = ui_dump_tree()
    tap_where(root, text="Edit")
    deadline = time.time() + 15
    while time.time() < deadline and editor_probe() is None:
        time.sleep(0.5)
    stub_get("/__test__/bump/web-2")  # someone else edited it
    root = ui_dump_tree()
    tap_where(root, text="Save")
    conflicted = has_text("changed in the cluster", 20, substring=True)
    root = ui_dump_tree()
    tap_where(root, text="Reload")
    time.sleep(3)  # reload GET lands; edit mode must be gone
    editor_gone = editor_probe() is None
    step("spec06-edit-conflict", conflicted and editor_gone,
         f"(dialog={conflicted} editor_gone={editor_gone})"
         if (conflicted and editor_gone) else _dump_debug(ui_dump_tree()))

    # -- invalid YAML is caught client-side: NO PUT leaves the device
    root = ui_dump_tree()
    tap_where(root, text="Edit")
    deadline = time.time() + 15
    while time.time() < deadline and editor_probe() is None:
        time.sleep(0.5)
    tap_editor()
    adb("shell", "input", "keyevent", "123")  # MOVE_END
    adb("shell", "input", "keyevent", "66")   # newline
    type_text("broken: [unclosed")
    puts_before = stub_get("/__test__/stats/web-2")["puts"]
    root = ui_dump_tree()
    tap_where(root, text="Save")
    shown_bad = has_text("could not be parsed", 15, substring=True)
    puts_after = stub_get("/__test__/stats/web-2")["puts"]
    root = ui_dump_tree()
    tap_where(root, text="Cancel")
    time.sleep(1)
    root = ui_dump_tree()
    tap_where(root, text="Discard")  # dirty-exit confirm
    time.sleep(1)
    step("spec06-edit-invalid", shown_bad and puts_after == puts_before,
         f"(shown={shown_bad} puts {puts_before}->{puts_after})"
         if (shown_bad and puts_after == puts_before)
         else _dump_debug(ui_dump_tree()))

    # -- delete web-multi from the detail top bar: dialog + caution, then the
    #    list invalidation tick removes the row
    adb("shell", "input", "keyevent", "4")  # detail -> list
    time.sleep(1)
    open_detail("web-multi")
    root = ui_dump_tree()
    tap_where(root, text="Delete")
    caution = has_text("terminated", 10, substring=True)  # pod cascade line
    root = ui_dump_tree()
    tap_where(root, **{"content-desc": "confirm-delete"})
    gone_detail = not has_text("web-multi", 15)
    st = stub_get("/__test__/stats/web-multi")
    step("spec06-delete-detail", caution and st["deleted"] and gone_detail,
         f"(caution={caution} stats={st} row_gone={gone_detail})"
         if (caution and st["deleted"] and gone_detail)
         else _dump_debug(ui_dump_tree()))

    # -- delete web-drop via the row overflow (same dialog, list entry point)
    _, drop_row = wait_node(text="web-drop")
    row_overflow_delete("web-drop")
    gone_list = not has_text("web-drop", 15)
    st = stub_get("/__test__/stats/web-drop")
    step("spec06-delete-list-row", st["deleted"] and gone_list,
         f"(stats={st} row_gone={gone_list})"
         if (st["deleted"] and gone_list) else _dump_debug(ui_dump_tree()))

    # -- delete web-protected: RBAC gate — 403 names the DELETE verb
    #    (web-nolog is a normal mutable pod; only its LOGS are 403, so the
    #    dedicated web-protected fixture is the forbidden-delete subject)
    #    Swipe to the end first: earlier deletions reflowed the rows and the
    #    overflow button must be on-screen for the content-desc tap to land.
    _, prot_row = wait_node(text="web-protected")
    if prot_row is not None:
        x, y = center(prot_row)
        adb("shell", "input", "swipe", str(x), str(y + 60), str(x), str(y - 300), "300")
        time.sleep(1)
    row_overflow_delete("web-protected")
    verb_msg = has_text("permission to delete", 20, substring=True)
    still_there = has_text("web-protected", 5)
    st = stub_get("/__test__/stats/web-protected")
    step("spec06-delete-forbidden",
         verb_msg and still_there and not st["deleted"],
         f"(verb_msg={verb_msg} row_kept={still_there} stats={st})"
         if (verb_msg and still_there and not st["deleted"])
         else _dump_debug(ui_dump_tree()))

    # back: list -> grid, then Secrets -> 403 escape hatch
    adb("shell", "input", "keyevent", "4")
    time.sleep(1)
    root, secrets_card = wait_node(text="Secrets")
    if secrets_card is not None:
        x, y = center(secrets_card)
        adb("shell", "input", "tap", str(x), str(y))
    shown = False
    deadline = time.time() + 20
    while time.time() < deadline and not shown:
        try:
            r = ui_dump(retries=3)
            shown = any("do not have permission" in (n.get("text") or "")
                        for n in r.iter("node"))
        except RuntimeError:
            time.sleep(1)
    step("spec04-forbidden-escape", shown,
         "" if shown else _dump_debug(ui_dump_tree()))

    # ---- network failure + retry recovery (spec 04 acceptance criteria) ----
    SERVER_PROC.terminate()
    try:
        SERVER_PROC.wait(timeout=10)
    except subprocess.TimeoutExpired:
        SERVER_PROC.kill()
    root = ui_dump_tree()
    tap_where(root, text="Retry")  # the Forbidden state's retry; re-GET now hits dead stub
    shown = False
    deadline = time.time() + 40  # give OkHttp its retry budget + backoff to fail
    while time.time() < deadline and not shown:
        try:
            r = ui_dump(retries=3)
            shown = any("Could not reach the cluster" in (n.get("text") or "")
                        for n in r.iter("node"))
        except RuntimeError:
            time.sleep(1)
    step("spec04-network-error", shown,
         "" if shown else _dump_debug(ui_dump_tree()))
    start_stub()
    root = ui_dump_tree()
    tap_where(root, text="Retry")
    recovered = False
    deadline = time.time() + 30
    while time.time() < deadline and not recovered:
        try:
            r = ui_dump(retries=3)
            recovered = any("do not have permission" in (n.get("text") or "")
                            for n in r.iter("node"))
        except RuntimeError:
            time.sleep(1)
    step("spec04-network-recovered", recovered,
         f"(secrets_hits={log_count('/api/v1/secrets')})"
         if recovered else _dump_debug(ui_dump_tree()))
    # back: secrets-list -> grid -> cluster list (delete flow needs the row)
    for _ in range(2):
        adb("shell", "input", "keyevent", "4")
        time.sleep(1)

    # ---- delete ----
    root, row = wait_node(text="accept-east")
    if row is not None:
        x, y = center(row)
        adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "1500")
        time.sleep(2)
        root = ui_dump_tree()
        if tap_where(root, text="Delete"):
            time.sleep(2)
            root, empty = wait_node(text="No cluster connected")
            step("delete-removes-row", empty is not None)
            ls = adb("shell", "run-as", PKG, "ls", "files/connections")
            step("delete-removes-blob",
                 "No such file" in ls.stderr or not ls.stdout.strip(),
                 f"(ls: {ls.stdout.strip()!r})")
        else:
            step("delete-confirm-tap", False)

    # ---- exec kubeconfig rejected ----
    baseline = log_count("GET /version")
    root, _ = wait_node(text="Paste kubeconfig")
    if root is not None:
        tap_where(root, text="Paste kubeconfig")
    # same retry-open loop as the first dialog: tap may race the render
    edits = []
    deadline = time.time() + 25
    while time.time() < deadline and not edits:
        root = ui_dump_tree()
        edits = find_edit_nodes(root)
        if not edits:
            tap_where(root, text="Paste kubeconfig")
            time.sleep(2)
    if not step("paste-dialog-2", bool(edits), _dump_debug(root) if not edits else ""):
        return
    x, y = center(edits[0])
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1.5)
    type_multiline(field_text, kubeconfig(ca_b64, with_exec=True))

    # the tall EditText can push the error Text below the dialog fold;
    # uiautomator only reports visible nodes — swipe up before polling
    adb("shell", "input", "swipe", str(x), str(y + 300), str(x), str(y - 300), "400")
    time.sleep(1)

    # validation error may render a moment after typing settles; poll for it
    shown = False
    deadline = time.time() + 20
    while time.time() < deadline and not shown:
        try:
            root = ui_dump(retries=3)
        except RuntimeError:
            time.sleep(1)
            continue
        shown = any("uses an exec credential plugin" in (n.get("text") or "")
                    for n in root.iter("node"))
        if not shown:
            time.sleep(1)
    detail = ""
    if not shown:
        field = field_text()
        detail = (f"(field tail: {(field or '')[-120:]!r})")
    step("exec-error-shown", shown, detail)

    root = ui_dump_tree()
    tap_where(root, text="Cancel")
    time.sleep(2)
    step("exec-nothing-persisted", log_count("GET /version") == baseline)
    ls = adb("shell", "run-as", PKG, "ls", "files/connections")
    step("exec-no-blob", "No such file" in ls.stderr or not ls.stdout.strip())


def finish():
    if failures:
        print(f"ACCEPTANCE_FAIL failing={failures}")
        return 1
    print("ACCEPTANCE_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
