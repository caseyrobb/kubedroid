#!/usr/bin/env python3
"""Sidecar screencap for fastlane store screenshots (spec 11 stage 2).

Runs on the maintainer build host alongside tools/acceptance/run-chain.sh.
Polls both acceptance emulators; when a target screen's marker text first
appears in the uiautomator hierarchy, captures one PNG. Deterministic:
screen detection is by content, and the acceptance flow is scripted, so
every screen appears at least once per leg.

Screens (marker -> file, with must_not guards against dialog states):
  "No cluster connected"      -> 1.png  (empty state)
  "accept-east"               -> 2.png  (cluster list, green probe)
  "Workloads"                 -> 3.png  (browse grid, official icons)
  "web-2"                     -> 4.png  (pod list)
  "kblog-gamma"               -> 5.png  (log viewer, 3 lines)

Usage: screenshot-monitor.py OUTDIR [--timeout 1800]
"""
import os
import subprocess
import sys
import time

ANDROID_HOME = os.path.expanduser("~/Android/Sdk")
ADB = f"{ANDROID_HOME}/platform-tools/adb"
# The sidecar's concurrent `uiautomator dump` can perturb the driver's own
# dumps/typing (observed: IME typing cascade failures on the API 34 leg).
# Confine the sidecar to the API 26 leg (emulator-5556), which reliably
# hosts every capture, and leave the other leg untouched as a clean
# acceptance signal.
SERIALS = ["emulator-5556"]

SCREENS = [
    # (file, marker, must_not, must_have)
    ("1.png", "No cluster connected", ["Import cluster", "Filter"], []),
    # "accept-east" alone also appears in the paste dialog's typed YAML and
    # right after import (toast + unloaded probe); require the fully
    # rendered list: no dialog, no toast, version chip loaded.
    ("2.png", "accept-east",
     ["Import cluster", "kubeconfig (YAML)", "Cluster added"], ["v1.36.4"]),
    # Browse grid only: section header "Workloads" never appears on the
    # pod list (whose title is "Pods").
    ("3.png", "Workloads", ["Import cluster", "kblog-"], []),
    ("4.png", "web-2", ["Import cluster", "kblog-"], []),
    ("5.png", "kblog-gamma", ["Import cluster"], []),
]


def adb(serial, *args, binary=False):
    return subprocess.run([ADB, "-s", serial, *args],
                          capture_output=True,
                          text=not binary)


def ui_dump(serial):
    r = adb(serial, "shell", "uiautomator", "dump", "/sdcard/kdmon.xml")
    if r.returncode != 0:
        return ""
    c = adb(serial, "shell", "cat", "/sdcard/kdmon.xml")
    return c.stdout if c.returncode == 0 else ""


def main():
    outdir = sys.argv[1]
    timeout = 1800
    if "--timeout" in sys.argv:
        timeout = int(sys.argv[sys.argv.index("--timeout") + 1])
    os.makedirs(outdir, exist_ok=True)
    done = {f for f in os.listdir(outdir)
            if os.path.getsize(os.path.join(outdir, f)) > 1000}
    deadline = time.time() + timeout
    reported = set()  # (fname, reason) already logged, to keep the log small
    while time.time() < deadline:
        pending = [s for s in SCREENS if s[0] not in done]
        if not pending:
            break
        for serial in SERIALS:
            if not pending:
                break
            if adb(serial, "get-state").stdout.strip() != "device":
                continue
            xml = ui_dump(serial)
            if not xml:
                continue
            for fname, marker, must_not, must_have in list(pending):
                if marker not in xml:
                    continue
                blocked = [b for b in must_not if b in xml]
                missed = [m for m in must_have if m not in xml]
                if blocked or missed:
                    reason = f"blocked={blocked} missed={missed}"
                    if (fname, reason) not in reported:
                        reported.add((fname, reason))
                        print(f"REJECT {fname} ({reason} on {serial})", flush=True)
                    continue
                png = adb(serial, "exec-out", "screencap", "-p", binary=True).stdout
                if png and png[:4] == b"\x89PNG":
                    with open(os.path.join(outdir, fname), "wb") as fh:
                        fh.write(png)
                    done.add(fname)
                    print(f"CAPTURED {fname} (marker {marker!r} on {serial})", flush=True)
                    pending.remove((fname, marker, must_not, must_have))
                break  # one capture per poll tick; screen lingers
        time.sleep(1.5)
    missing = [f for f, _, _, _ in SCREENS if f not in done]
    print("MONITOR_DONE captured=%d missing=%s" % (len(done), missing or "none"), flush=True)
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
