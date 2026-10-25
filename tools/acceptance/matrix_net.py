#!/usr/bin/env python3
"""One controlled matrix: persistent stub + persistent emulator, with
host-side liveness check before EVERY network probe, so no result is ever
produced against a dead stub. Debug aid for the acceptance networking."""
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.expanduser("~/kubedroid/tools/acceptance"))
import acceptance as A  # noqa: E402

ADB = A.adb


def alive():
    """Host-side TLS liveness check against the stub; True if reachable."""
    r = subprocess.run(
        ["openssl", "s_client", "-connect", "127.0.0.1:6443",
         "-CAfile", f"{A.WORK}/ca-cert.pem", "-brief"],
        input="", capture_output=True, text=True, timeout=10)
    # s_client -brief writes its summary to stderr, not stdout
    return "CONNECTION ESTABLISHED" in (r.stdout or "") + (r.stderr or "")


def wait_alive(label):
    t0 = time.time()
    while time.time() - t0 < 15:
        if alive():
            print(f"[{label}] stub ALIVE")
            return True
        time.sleep(1)
    print(f"[{label}] stub DEAD <<<<")
    return False


def main():
    env = dict(os.environ, ANDROID_HOME=A.ANDROID_HOME)
    if os.path.exists(A.LOG):
        os.remove(A.LOG)
    server = subprocess.Popen(
        [sys.executable, A.SERVER, str(A.PORT), A.LOG,
         f"{A.WORK}/server-cert.pem", f"{A.WORK}/server-key.pem"],
        env=env)
    emu = subprocess.Popen(
        [A.EMULATOR, "-avd", "kv37", "-port", "5558", "-no-window", "-no-audio",
         "-no-boot-anim", "-gpu", "swiftshader_indirect"],
        env=env, stdout=open("/tmp/emu-m.log", "w"), stderr=subprocess.STDOUT)
    try:
        A.adb("wait-for-device")
        t0 = time.time()
        while time.time() - t0 < 240:
            if A.adb("shell", "getprop", "sys.boot_completed").stdout.strip() == "1":
                break
            time.sleep(3)
        print("BOOTED")

        A.adb("install", "-r", A.APK)

        assert wait_alive("boot")

        print("-- shell nc --")
        print(A.adb("shell", "toybox", "nc", "-w", "3", "10.0.2.2", "6443").stdout,
              "rc=", A.adb("shell", "toybox", "nc", "-w", "3", "10.0.2.2",
                           "6443").returncode)
        assert wait_alive("post-shell-nc")

        for i in (1, 2):
            print(f"-- app-uid nc #{i} --")
            r = A.adb("shell", "run-as", A.PKG, "toybox", "nc", "-w", "3",
                      "10.0.2.2", "6443")
            print("out=", repr(r.stdout), "err=", repr(r.stderr), "rc=", r.returncode)
            assert wait_alive(f"post-app-nc-{i}")

        # real app probe path: launch app, verify INTERNET granted
        A.adb("shell", "am", "force-stop", A.PKG)
        A.adb("shell", "am", "start", "-n", f"{A.PKG}/{A.ACTIVITY}")
        time.sleep(6)
        perms = A.adb("shell", "dumpsys", "package", A.PKG)
        for line in perms.stdout.splitlines():
            if "INTERNET" in line:
                print("PERM:", line.strip()[:120])
        assert wait_alive("end")
        print("MATRIX_DONE")
    finally:
        print("===STUBLOG===")
        try:
            print(open(A.LOG).read()[-600:])
        except FileNotFoundError:
            print("(none)")
        server.terminate()
        A.adb("emu", "kill")
        emu.terminate()


if __name__ == "__main__":
    main()
