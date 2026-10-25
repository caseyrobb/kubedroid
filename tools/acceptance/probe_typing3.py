#!/usr/bin/env python3
"""Bisect which part of `    token: acceptance-test-token` makes
`adb input text` vanish. Each case: fresh app state, open paste dialog,
verify EditText focus, type one line, dump field. Debug aid."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ANDROID_HOME = os.path.expanduser("~/Android/Sdk")
ADB = f"{ANDROID_HOME}/platform-tools/adb"
EMULATOR = f"{ANDROID_HOME}/emulator/emulator"
SERIAL = "emulator-5558"
PKG = "com.neozeon.kubedroid.debug"


def adb(*a):
    return subprocess.run([ADB, "-s", SERIAL, *a], capture_output=True,
                          text=True)


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    x = adb("shell", "cat", "/sdcard/ui.xml").stdout
    try:
        return ET.fromstring(x[x.index("<hierarchy"):
                               x.rindex("</hierarchy>") + 12])
    except ValueError:
        return ET.fromstring("<hierarchy/>")


def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def wait_text(label, timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        r = dump()
        for n in r.iter("node"):
            if (n.get("text") or "") == label:
                return n
        time.sleep(2)
    return None


def edit_node():
    for n in dump().iter("node"):
        if (n.get("class") or "").endswith("EditText"):
            return n
    return None


def case(label, line):
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "pm", "clear", PKG)
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    time.sleep(1)
    adb("shell", "am", "start", "-n", f"{PKG}/com.neozeon.kubedroid.MainActivity")
    n = wait_text("Paste kubeconfig", timeout=40)
    if n is None:  # relaunch once — cold start after pm clear sometimes stalls
        adb("shell", "am", "force-stop", PKG)
        adb("shell", "am", "start", "-n", f"{PKG}/com.neozeon.kubedroid.MainActivity")
        n = wait_text("Paste kubeconfig", timeout=40)
    if n is None:
        print(f"CASE {label}: NO-PASTE-BUTTON", flush=True)
        return
    for _ in range(8):
        x, y = center(n)
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.5)
        ed = edit_node()
        if ed is not None:
            break
        n2 = wait_text("Paste kubeconfig", timeout=5)
        if n2 is not None:
            n = n2
    if ed is None:
        print(f"CASE {label}: NO-EDITTEXT", flush=True)
        return
    x, y = center(ed)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.8)
    focused = (edit_node().get("focused") or "?") if edit_node() else "?"
    adb("shell", "input", "text", line.replace(" ", "%s"))
    adb("shell", "input", "keyevent", "66")
    time.sleep(0.7)
    got = line.strip() in (edit_node().get("text") or "") if edit_node() else False
    txt = (edit_node().get("text") or "") if edit_node() else ""
    print(f"CASE {label}: line={line!r} landed={got} focused={focused} "
          f"field={txt!r}", flush=True)


def main():
    env = dict(os.environ, ANDROID_HOME=ANDROID_HOME)
    emu = subprocess.Popen(
        [EMULATOR, "-avd", "kv37", "-port", "5558", "-no-window", "-no-audio",
         "-no-boot-anim", "-gpu", "swiftshader_indirect"],
        env=env, stdout=open("/tmp/emu-probe3.log", "w"),
        stderr=subprocess.STDOUT)
    try:
        adb("wait-for-device")
        t0 = time.time()
        while time.time() - t0 < 240:
            if adb("shell", "getprop", "sys.boot_completed").stdout.strip() == "1":
                break
            time.sleep(3)
        adb("shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "0")
        case("v1-full", "    token: acceptan…oken")
        case("v2-token2", "    token2: low-token")
        case("v3-nohyphen", "    token: acceptance")
        case("v4-otherkey", "    aaa: acceptan…oken")
        case("v5-underscore", "    token: ACCE…OKEN")
        case("v6-shortval", "    token: at")
        case("v7-nospace", "token: acceptan…oken")
        case("v9-hex", "    token: 0f3a9c7e2b8d41e6")
        adb("shell", "settings", "put", "secure", "auto_replace", "0")
        adb("shell", "pm", "disable", "com.android.inputmethod.latin/.LatinIME")
        time.sleep(2)
        case("v10-ime-off", "    token: acceptan…oken")
        case("v11-ime-off-b64", "    certificate-authority-data: LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tCk1JSUJrakNDQVRlZ0F3SUJBZ0lVd2VGc1p6cWdDSW4xUjZFc3hQQTU0QUpMZWR0Vks3OWpZZ1h4bC93VzBk")
        adb("shell", "pm", "enable", "com.android.inputmethod.latin/.LatinIME")
    finally:
        adb("emu", "kill")
        emu.terminate()
    print("PROBE3_DONE")


if __name__ == "__main__":
    main()
