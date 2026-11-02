#!/usr/bin/env bash
# spec 12 debug: single API-26 acceptance leg with the screencap sidecar,
# keeping reject dumps. Lives in a file because an inline ssh command whose
# text contains the pgrep targets self-matches and kills the session
# (same reason run-chain.sh exists).
set -u
export ANDROID_HOME=~/Android/Sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
cd ~/kubedroid || exit 1

for p in $(pgrep -f 'accept[a]nce.py' 2>/dev/null); do kill "$p" 2>/dev/null; done
for p in $(pgrep -f 'accept[-]server.py' 2>/dev/null); do kill "$p" 2>/dev/null; done
for p in $(pgrep -f 'screenshot-monitor[.]py' 2>/dev/null); do kill "$p" 2>/dev/null; done
adb -s emulator-5556 emu kill 2>/dev/null
adb -s emulator-5558 emu kill 2>/dev/null
for p in $(pgrep -f 'emulator[-] -avd kv' 2>/dev/null); do kill "$p" 2>/dev/null; done
sleep 3

rm -rf /tmp/kdmon2
mkdir -p /tmp/kdmon2
nohup python3 tools/fdroid/screenshot-monitor.py /tmp/kdmon2 --timeout 1500 \
  > /tmp/kdmon2.log 2>&1 &
MON=$!

python3 tools/acceptance/acceptance.py 26 > /tmp/accept26dbg.log 2>&1
echo "ACCEPTANCE_EXIT=$?"
grep ACCEPTANCE /tmp/accept26dbg.log || tail -5 /tmp/accept26dbg.log
sleep 5
grep -E "REJECT|CAPTURED|MONITOR_DONE" /tmp/kdmon2.log | head -20
ls -la /tmp/kdmon2
