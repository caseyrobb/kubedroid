#!/usr/bin/env bash
# spec 05 acceptance chain: cleanup leftovers, then API 37 + API 26 runs.
# Lives on the devbox so the ssh command line never contains the strings
# pgrep searches for (self-match killed the previous inline attempt).
set -u
export ANDROID_HOME=~/Android/Sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
cd ~/kubedroid || exit 1

# kill leftovers (drivers, stubs, emulators) by explicit PID
for p in $(pgrep -f 'accept[a]nce.py' 2>/dev/null); do kill "$p" 2>/dev/null; done
for p in $(pgrep -f 'accept[-]server.py' 2>/dev/null); do kill "$p" 2>/dev/null; done
adb -s emulator-5558 emu kill 2>/dev/null
adb -s emulator-5556 emu kill 2>/dev/null
for p in $(pgrep -f 'emulator[-] -avd kv' 2>/dev/null); do kill "$p" 2>/dev/null; done
sleep 3

python3 tools/acceptance/acceptance.py 37 > /tmp/accept37.log 2>&1
echo DONE37
python3 tools/acceptance/acceptance.py 26 > /tmp/accept26.log 2>&1
echo DONE26
grep ACCEPTANCE /tmp/accept37.log
grep ACCEPTANCE /tmp/accept26.log
