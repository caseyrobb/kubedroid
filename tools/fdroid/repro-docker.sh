#!/usr/bin/env bash
# Spec 11 stage 3 (container leg): reproducibility inside the F-Droid
# build-server image. Builds the signed release twice from a clean tree in
# registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie (the image
# class F-Droid rebuilders use) and requires identical APK hashes.
#
# Requires docker on the build host. Mirrors repro-check.sh (which runs on
# the maintainer host toolchain) so both the local recipe AND the F-Droid
# environment class are covered before the fdroiddata MR.
#
# Env: KUBEDROID_BUILD_HOST         user@host with docker (see spec 10)
#      KUBEDROID_KEYSTORE_PROPERTIES path ON THE BUILD HOST to the spec 09
#                                    properties file
#      KUBEDROID_KEYSTORE_FILE       path ON THE BUILD HOST to the keystore
#      KUBEDROID_REPRO_IMAGE         override the builder image (optional)
#      KUBEDROID_REPRO_NEGATIVE=1    inject a source perturbation between
#                                    legs; the run MUST report
#                                    REPRO_CHECK_FAIL (negative control,
#                                    spec 11 stage 3 Testing)
set -euo pipefail
HOST="${KUBEDROID_BUILD_HOST:?KUBEDROID_BUILD_HOST not set (see docs/10-release-hosting-and-public-repo.md)}"
KS_PROPS="${KUBEDROID_KEYSTORE_PROPERTIES:?KUBEDROID_KEYSTORE_PROPERTIES not set (path on the build host)}"
KS_FILE="${KUBEDROID_KEYSTORE_FILE:?KUBEDROID_KEYSTORE_FILE not set (keystore path on the build host)}"
IMAGE="${KUBEDROID_REPRO_IMAGE:-registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie}"
NEGATIVE="${KUBEDROID_REPRO_NEGATIVE:-0}"
REPO="$(git rev-parse --show-toplevel)"
# Optional: build a specific ref (the release tag) instead of the working
# tree — F-Droid rebuilds from the tagged commit, so the gate should be
# able to prove reproducibility of exactly that tree.
REF="${KUBEDROID_REPRO_REF:-}"
if [ -n "$REF" ]; then
  git -C "$REPO" rev-parse -q --verify "$REF^{commit}" >/dev/null \
    || { echo "REPRO_CHECK_FAIL: ref $REF not found" >&2; exit 1; }
  # F-Droid rebuilders do a git clone of the Repo: URL, so the container
  # tree must include .git and live under a project dir named 'kubedroid'
  # (Gradle project name -> Kotlin module metadata -> dex layout).
  CLONE_URL="${KUBEDROID_REPRO_CLONE_URL:-https://github.com/caseyrobb/kubedroid.git}"
  STAGE="$REPO/.repro-stage"
  rm -rf "$STAGE" && mkdir -p "$STAGE/kubedroid"
  git clone -q --no-checkout "$CLONE_URL" "$STAGE/kubedroid"
  git -C "$STAGE/kubedroid" checkout -q --detach "$REF"
  SRC="$STAGE"
else
  git -C "$REPO" diff --quiet && git -C "$REPO" diff --cached --quiet \
    || { echo "REPRO_CHECK_FAIL: dirty tree — commit first" >&2; exit 1; }
  rm -rf "$REPO/.repro-stage"
  mkdir -p "$REPO/.repro-stage/kubedroid"
  rsync -a --exclude .git --exclude build --exclude local.properties \
    --exclude '.gradle*' "$REPO/" "$REPO/.repro-stage/kubedroid/"
  SRC="$REPO/.repro-stage"
fi

rsync -a --delete --exclude build --exclude local.properties \
  --exclude '.gradle*' "$SRC/" "$HOST:~/kubedroid-repro/"
scp -q "$REPO/tools/fdroid/repro-docker-impl.sh" "$HOST:~/kubedroid-repro/.repro-impl.sh"
[ -z "${STAGE:-}" ] || rm -rf "$STAGE"

# shellcheck disable=SC2029
ssh "$HOST" \
  "KUBEDROID_REPRO_IMAGE='$IMAGE' KUBEDROID_REPRO_NEGATIVE='$NEGATIVE' KUBEDROID_KS_PROPS_HOST='$KS_PROPS' KUBEDROID_KS_FILE_HOST='$KS_FILE' bash ~/kubedroid-repro/.repro-impl.sh"
