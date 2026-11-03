#!/usr/bin/env bash
# Capture fastlane store screenshots (spec 11 stage 2) on the maintainer
# build host: runs the standard acceptance chain with a screencap sidecar
# that grabs each screen as the scripted flow passes through it, then
# scp's the PNGs back. Run from the git canonical.
#
# Usage: capture-screenshots.sh [LOCAL_OUTDIR]
set -euo pipefail
HOST="${KUBEDROID_BUILD_HOST:?KUBEDROID_BUILD_HOST not set (see docs/10-release-hosting-and-public-repo.md)}"
BUILD_SCRIPT="${KUBEDROID_BUILD_SCRIPT:-~/kubedroid-build.sh}"  # gradle wrapper on the build host
OUT="${1:-fastlane/metadata/android/en-US/images/phoneScreenshots}"

REMOTE=/tmp/kubedroid-shots
# Full tree sync + debug build: acceptance.py installs
# build/outputs/apk/debug/kubedroid-debug.apk but NEVER builds it, so the
# chain would otherwise run against a stale APK from the last manual build.
rsync -a --delete --exclude .git --exclude build --exclude local.properties \
  --exclude '.gradle*' ./ "$HOST:~/kubedroid/"
ssh "$HOST" "bash -l $BUILD_SCRIPT :assembleDebug" 2>&1 | tail -1
ssh "$HOST" "rm -rf $REMOTE && mkdir -p $REMOTE"

# sidecar: capture while the chain runs the scripted flow
ssh "$HOST" "nohup python3 ~/kubedroid/tools/fdroid/screenshot-monitor.py $REMOTE --timeout 2400 > /tmp/kdmon.log 2>&1 &"
ssh "$HOST" "bash ~/kubedroid/tools/acceptance/run-chain.sh" || true
sleep 5
ssh "$HOST" "grep MONITOR_DONE /tmp/kdmon.log || tail -3 /tmp/kdmon.log"

mkdir -p "$OUT"
rm -f "$OUT"/*.png
# shellcheck disable=SC2029
ssh "$HOST" "ls $REMOTE" | grep -E '^[0-9]+\.png$' | while read -r f; do
  scp -q "$HOST:$REMOTE/$f" "$OUT/$f"
done
ls -la "$OUT"
echo "SCREENSHOTS_DONE"
