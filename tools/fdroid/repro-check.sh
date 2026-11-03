#!/usr/bin/env bash
# Spec 11 stage 3: A/B reproducibility gate. Builds the signed release
# twice from a clean tree on the maintainer build host and requires the
# two APKs to hash identically. Deterministic signing is possible because
# the release key is RSA (PKCS#1 v1.5) and dependenciesInfo is disabled
# (see docs/11-fdroid-distribution.md stage 3).
#
# Env: KUBEDROID_BUILD_HOST (user@host), KUBEDROID_KEYSTORE_PROPERTIES
#      (absolute path ON THE BUILD HOST to the spec 09 properties file).
set -euo pipefail
HOST="${KUBEDROID_BUILD_HOST:?KUBEDROID_BUILD_HOST not set (see docs/10-release-hosting-and-public-repo.md)}"
BUILD_SCRIPT="${KUBEDROID_BUILD_SCRIPT:-~/kubedroid-build.sh}"  # gradle wrapper on the build host
KS_PROPS="${KUBEDROID_KEYSTORE_PROPERTIES:?KUBEDROID_KEYSTORE_PROPERTIES not set (path on the build host)}"
REPO="$(git rev-parse --show-toplevel)"

git -C "$REPO" diff --quiet && git -C "$REPO" diff --cached --quiet \
  || { echo "REPRO_CHECK_FAIL: dirty tree — commit first" >&2; exit 1; }

rsync -a --delete --exclude .git --exclude build --exclude local.properties \
  --exclude '.gradle*' "$REPO/" "$HOST:~/kubedroid/"

# shellcheck disable=SC2029
ssh "$HOST" "
  set -euo pipefail
  cd ~/kubedroid
  export KUBEDROID_KEYSTORE_PROPERTIES='$KS_PROPS'
  # Both legs start from a wiped build dir: an F-Droid rebuilder works from
  # a fresh clone, so an incremental A would not prove from-scratch builds.
  rm -rf build
  bash -l $BUILD_SCRIPT :assembleRelease >/tmp/reproA.log 2>&1 \
    || { tail -20 /tmp/reproA.log; exit 1; }
  cp build/outputs/apk/release/*.apk /tmp/repro-A.apk
  rm -rf build
  bash -l $BUILD_SCRIPT :assembleRelease >/tmp/reproB.log 2>&1 \
    || { tail -20 /tmp/reproB.log; exit 1; }
  cp build/outputs/apk/release/*.apk /tmp/repro-B.apk
  HA=\$(sha256sum /tmp/repro-A.apk | cut -d' ' -f1)
  HB=\$(sha256sum /tmp/repro-B.apk | cut -d' ' -f1)
  echo \"A=\$HA\"
  echo \"B=\$HB\"
  if [ \"\$HA\" = \"\$HB\" ]; then echo REPRO_CHECK_PASS; else echo REPRO_CHECK_FAIL; exit 1; fi
"
