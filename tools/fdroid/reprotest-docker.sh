#!/usr/bin/env bash
# Spec 11 stage 3: run `reprotest` (control vs env-varied builds) inside the
# F-Droid build-server image on the build host. Companion to
# repro-docker.sh (A/B hash gate); this adds reprotest's environment
# variations (hostname, user/group, time, build path, HOME, umask, env).
#
# Usage (from the repo on the maintainer workstation):
#   KUBEDROID_BUILD_HOST=<user@buildhost> \
#   KUBEDROID_KEYSTORE_PROPERTIES=<props path on host> \
#   KUBEDROID_KEYSTORE_FILE=<keystore path on host> \
#   KUBEDROID_REPRO_REF=vX.Y.Z bash tools/fdroid/reprotest-docker.sh
#
# Optional: KUBEDROID_REPRO_IMAGE (default registry.gitlab.com/fdroid/
# fdroidserver:buildserver-trixie), KUBEDROID_REPRO_CLONE_URL (default
# https://github.com/caseyrobb/kubedroid.git), KUBEDROID_REPRO_REF (default
# the checked-out HEAD commit).
set -euo pipefail
BUILD_HOST="${KUBEDROID_BUILD_HOST:?set KUBEDROID_BUILD_HOST=user@buildhost}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ssh -o BatchMode=yes "$BUILD_HOST" \
  KUBEDROID_KEYSTORE_PROPERTIES="${KUBEDROID_KEYSTORE_PROPERTIES:?}" \
  KUBEDROID_KEYSTORE_FILE="${KUBEDROID_KEYSTORE_FILE:?}" \
  KUBEDROID_REPRO_IMAGE="${KUBEDROID_REPRO_IMAGE:-registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie}" \
  KUBEDROID_REPRO_CLONE_URL="${KUBEDROID_REPRO_CLONE_URL:-https://github.com/caseyrobb/kubedroid.git}" \
  KUBEDROID_REPRO_REF="${KUBEDROID_REPRO_REF:-HEAD}" \
  "mkdir -p ~/kubedroid-reprotest/tools"
scp -q "$HERE"/reprotest-docker-impl.sh \
      "$HERE"/reprotest-build.sh \
      "$HERE"/reprotest-sdk-bootstrap.sh \
      "$BUILD_HOST":~/kubedroid-reprotest/tools/
ssh -o BatchMode=yes "$BUILD_HOST" 'bash ~/kubedroid-reprotest/tools/reprotest-docker-impl.sh'
