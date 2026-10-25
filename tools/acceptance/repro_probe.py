#!/usr/bin/env python3
"""Repro one paste-import on kv37 and print the row status text + logcat
exceptions, to see WHY the /version probe never reaches the stub."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.expanduser("~/kubedroid/tools/acceptance"))
import acceptance as A  # noqa: E402


def dump():
    return A.ui_dump()


def main():
    import subprocess as _sp
    ca_key, ca_crt, srv_key, srv_crt, b64 = A.make_certs()
    if os.path.exists(A.LOG):
        os.remove(A.LOG)
    server = _sp.Popen(
        [sys.executable, A.SERVER, str(A.PORT), A.LOG,
         f"{A.WORK}/server-cert.pem", f"{A.WORK}/server-key.pem"],
        env=dict(os.environ, ANDROID_HOME=A.ANDROID_HOME))
    t0 = time.time()
    while time.time() - t0 < 10 and A.log_count("SERVER_START") == 0:
        time.sleep(0.3)
    assert A.log_count("SERVER_START") == 1, "stub did not start"
    env = dict(os.environ, ANDROID_HOME=A.ANDROID_HOME)
    emu = subprocess.Popen(
        [A.EMULATOR, "-avd", "kv37", "-port", "5558", "-no-window",
         "-no-audio", "-no-boot-anim", "-gpu", "swiftshader_indirect"],
        env=env, stdout=open("/tmp/emu-repro.log", "w"),
        stderr=subprocess.STDOUT)
    try:
        A.adb("wait-for-device")
        t0 = time.time()
        while time.time() - t0 < 240:
            if A.adb("shell", "getprop", "sys.boot_completed").stdout.strip() == "1":
                break
            time.sleep(3)
        A.adb("install", "-r", A.APK)
        A.adb("shell", "pm", "clear", A.PKG)
        A.adb("logcat", "-c")
        A.adb("shell", "am", "start", "-n", f"{A.PKG}/{A.ACTIVITY}")
        time.sleep(8)
        eds = []
        deadline = time.time() + 40
        while time.time() < deadline and not eds:
            root = dump()
            eds = A.find_edit_nodes(root)
            if not eds:
                A.tap_where(root, text="Paste kubeconfig")
                time.sleep(2)
        assert eds, "no edit node"
        x, y = A.center(eds[0])
        A.adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1)

        def ft():
            try:
                r = A.ui_dump(retries=2)
            except RuntimeError:
                return None
            for n in r.iter("node"):
                if (n.get("class") or n.get("className") or "").endswith("EditText"):
                    return n.get("text") or ""
            return None

        A.type_multiline(ft, A.kubeconfig(b64))
        time.sleep(1)
        root = dump()
        # start in-guest packet capture just before the probe fires
        A.adb("shell", "su", "0", "sh", "-c",
               "tcpdump -i eth0 -nn port 6443 -w /data/local/tmp/g.pcap "
               ">/dev/null 2>&1 &")
        time.sleep(2)
        A.tap_where(root, text="Import cluster")
        time.sleep(15)
        for label, extra in (("UI@15s", 0), ("UI@35s", 20)):
            if extra:
                time.sleep(extra)
            root = dump()
            texts = [(n.get("text") or "").strip() for n in root.iter("node")]
            print(f"{label}:")
            for t in texts:
                if t:
                    print("  ", repr(t[:160]))
        A.adb("shell", "su", "0", "pkill", "tcpdump")
        time.sleep(1)
        cap = A.adb("shell", "su", "0", "tcpdump", "-nn", "-r",
                    "/data/local/tmp/g.pcap").stdout
        print("===GUEST-PCAP===")
        print(cap[:1500])
        print("===LOGCAT===")
        lc = A.adb("logcat", "-d").stdout
        noise = (" GC ", "ImeTracker", "FrameTracker", "WindowManager",
                 "ActivityTaskManager", "nativeloader", "adbd", "MediaProvider",
                 "ClassLoaderContext", "CoreBackPreview", "ImeBackCallback",
                 "ProfileInstaller", "GraphicsEnvironment", "Zygote",
                 "hiddenapi", "Compiler allocated", "InteractionJank")
        for line in lc.splitlines():
            if "kubedroid" not in line and "System.err" not in line \
                    and "AndroidRuntime" not in line:
                continue
            if any(n in line for n in noise):
                continue
            print(line[:300])
        print("===STUBLOG===")
        try:
            print(open("/tmp/kubedroid-accept-server.log").read()[-800:])
        except FileNotFoundError:
            print("(none)")
    finally:
        A.adb("emu", "kill")
        emu.terminate()
        try:
            server.terminate()
        except NameError:
            pass
    print("REPRO_DONE")


if __name__ == "__main__":
    main()
