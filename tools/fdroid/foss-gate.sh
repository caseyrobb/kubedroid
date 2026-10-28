#!/usr/bin/env bash
# kubedroid FOSS gate (spec 11 stage 1).
#
# F-Droid rejects non-FOSS dependencies outright. This gate fails if any
# denied (known non-FOSS) coordinate group appears in the dependency
# catalog / build script, or if the manifest requests the CAMERA
# permission (the app must stay camera-free; the ML Kit scanner that once
# required it was removed with QR import — spec 03 stage 10).
#
# Usage: foss-gate.sh [--toml PATH] [--gradle PATH] [--manifest PATH]
# Exit 0 = FOSS-clean; 1 = violation printed to stdout.
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOML="$DIR/gradle/libs.versions.toml"
GRADLE="$DIR/build.gradle.kts"
MANIFEST="$DIR/src/main/AndroidManifest.xml"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --toml) TOML="$2"; shift 2 ;;
    --gradle) GRADLE="$2"; shift 2 ;;
    --manifest) MANIFEST="$2"; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done

# Known non-FOSS / proprietary artifact groups (extend deliberately).
DENY=(
  com.google.android.gms      # Play Services (proprietary)
  com.google.firebase         # Firebase (proprietary)
  com.google.mlkit            # ML Kit bundled models (closed-source binary)
  com.google.ar               # ARCore (proprietary)
  com.google.android.gcm      # legacy GCM client (proprietary)
  com.facebook.ads            # proprietary ad SDK
  com.appsflyer               # proprietary analytics/attribution
  com.braze.infra.sdk         # proprietary engagement SDK
)

fail=0
for grp in "${DENY[@]}"; do
  if grep -nE "\"$grp[.\"]" "$TOML" "$GRADLE" 2>/dev/null | grep -v '^\s*#' ; then
    echo "FOSS_VIOLATION: denied group $grp (spec 11)"
    fail=1
  fi
done

if grep -q 'android.permission.CAMERA' "$MANIFEST"; then
  echo "FOSS_VIOLATION: manifest requests CAMERA permission (spec 03 stage 10: app must be camera-free)"
  fail=1
fi

if [[ $fail -eq 0 ]]; then
  echo "FOSS_GATE_PASS"
fi
exit $fail
