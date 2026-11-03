#!/usr/bin/env bash
# kubedroid release publisher (spec 10).
#
# Runs on the git canonical machine. Drives the maintainer build host over
# ssh for the gradle build (same flow as the daily dev loop), gates the
# artifact on signature + checksum + version/tag consistency + the public
# hygiene gate, then publishes a release with the APK + SHA256SUMS.
#
# Usage:  publish.sh vX.Y.Z [--dry-run]
#
# Required env (real run):  KUBEDROID_BUILD_HOST (ssh target),
#                           KUBEDROID_RELEASE_UPLOAD_TOKEN (never logged),
#                           plus per backend:
#                             gitea:  KUBEDROID_GITEA_URL (https://host)
#                             github: KUBEDROID_REPO_SLUG=caseyrobb/kubedroid
#                                     (API https://api.github.com)
# --dry-run builds + verifies everything and prints the planned release
# without touching git tags or the network (token not required).
#
# Secrets policy: token is read from the environment once and passed to
# curl via a header file; it never appears in argv, logs, or git objects.
set -euo pipefail

REPO_SLUG="${KUBEDROID_REPO_SLUG:-crobb/kubedroid}"
CERT_SHA256="8aa62040e78074602176340b3bb8c46c7ceab5b8c3a9d485a5ae45dd0593acd2"  # spec 09 key; update only on deliberate rotation
OUT_DIR="${KUBEDROID_RELEASE_DIR:-$HOME/kubedroid-builds}"
BUILD_TASKS=":assembleRelease"
HYGIENE_PATTERN='devbox|GB10|redcomet\.ca|172\.16\.|neozeon\.ca|fenrir|gitea\.neozeon|wiki\.neozeon'
HYGIENE_EXCLUDE='tools/release/'  # gate impl necessarily contains its own patterns

die() { echo "PUBLISH_FAIL: $*" >&2; exit 1; }
note() { echo "publish: $*"; }

VERSION="${1:-}"; MODE="${2:-}"
[[ "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]] || die "usage: publish.sh vX.Y.Z [--dry-run]"
NAME="${VERSION#v}"

# ---- repo gates ----------------------------------------------------------
git diff --quiet && git diff --cached --quiet || die "worktree must be clean"
git rev-parse -q --verify "refs/tags/$VERSION" >/dev/null \
  || die "tag $VERSION not found — create it first: git tag $VERSION && git push origin $VERSION"
[[ "$(git rev-parse "$VERSION^{commit}")" == "$(git rev-parse HEAD)" ]] \
  || die "HEAD must be the tagged commit $VERSION"

VNAME="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' build.gradle.kts)"
[[ "$VNAME" == "$NAME" ]] || die "build.gradle.kts versionName '$VNAME' != tag '$NAME'"
note "version gate ok: $VERSION"

# Public-hygiene gate (spec 10): no homelab identifiers outside MAINTAINERS.
if git grep -n -iE "$HYGIENE_PATTERN" -- 'docs/0*' 'src/*' 'tools/*' README.md CONTRIBUTING.md SECURITY.md \
     | grep -vE "$HYGIENE_EXCLUDE"; then
  die "public-hygiene gate: internal identifiers found above"
fi
note "public-hygiene gate ok"

# FOSS gate (spec 11): no non-FOSS coordinates, no CAMERA permission.
tools/fdroid/foss-gate.sh || die "FOSS gate failed (spec 11)"
note "FOSS gate ok"

# fastlane/version gates (spec 11 stage 2, decisions Q5):
VC="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' build.gradle.kts)"
[[ -n "$VC" ]] || die "versionCode not found in build.gradle.kts"
PREV_TAG="$(git describe --tags --abbrev=0 "$VERSION^" 2>/dev/null || true)"
if [[ -n "$PREV_TAG" ]]; then
  PREV_VC="$(git show "$PREV_TAG:build.gradle.kts" | sed -n 's/.*versionCode = \([0-9]*\).*/\1/p')"
  (( VC > PREV_VC )) || die "versionCode $VC must exceed $PREV_VC (tag $PREV_TAG) — F-Droid requires monotonic increases"
fi
SD="fastlane/metadata/android/en-US/short_description.txt"
[[ -f "$SD" ]] || die "missing $SD (fastlane metadata, spec 11)"
SD_TEXT="$(head -1 "$SD")"
(( ${#SD_TEXT} <= 80 )) || die "short_description exceeds 80 chars"
[[ "$SD_TEXT" != *. ]] || die "short_description must not end with a dot"
[[ -f "fastlane/metadata/android/en-US/changelogs/$VC.txt" ]] \
  || die "fastlane changelogs/$VC.txt missing for versionCode $VC"
note "fastlane/version gates ok (versionCode $VC)"

# ---- build on the build host --------------------------------------------
[[ -n "${KUBEDROID_BUILD_HOST:-}" ]] || die "KUBEDROID_BUILD_HOST not set (see docs/10-release-hosting-and-public-repo.md)"
# Path (ON THE BUILD HOST) of the spec 09 signing properties; forwarded to
# the build so assembleRelease signs. Without it the build is unsigned and
# the signature gate below fails closed.
SIGN_ENV=""
if [[ -n "${KUBEDROID_KEYSTORE_PROPERTIES:-}" ]]; then
  [[ "$KUBEDROID_KEYSTORE_PROPERTIES" =~ ^/[A-Za-z0-9._/-]+$ ]] || die "KUBEDROID_KEYSTORE_PROPERTIES must be a plain absolute path"
  SIGN_ENV="KUBEDROID_KEYSTORE_PROPERTIES=$KUBEDROID_KEYSTORE_PROPERTIES "
fi
LOCAL_ROOT="$(pwd)"
rsync -a --exclude .git --exclude build "$LOCAL_ROOT/" "$KUBEDROID_BUILD_HOST:~/kubedroid/"
note "tree synced to build host"
ssh "$KUBEDROID_BUILD_HOST" \
  "cd ~/kubedroid && $SIGN_ENV bash -l ~/devbox-build.sh $BUILD_TASKS 2>&1 | tail -2 && ls build/outputs/apk/release/" \
  | tee /tmp/kubedroid-publish-build.log
grep -q "BUILD_EXIT=0" /tmp/kubedroid-publish-build.log || die "release build failed on build host"

APK_NAME="kubedroid-release.apk"
mkdir -p "$OUT_DIR"
scp -q "$KUBEDROID_BUILD_HOST:~/kubedroid/build/outputs/apk/release/$APK_NAME" \
    "$OUT_DIR/kubedroid-release-$VERSION.apk"

# ---- artifact gates ------------------------------------------------------
APK="$OUT_DIR/kubedroid-release-$VERSION.apk"
[[ -s "$APK" ]] || die "APK missing"

APKSIGNER="$(ssh "$KUBEDROID_BUILD_HOST" 'ls ~/Android/Sdk/build-tools/*/apksigner | sort | tail -1')"
SIG="$(ssh "$KUBEDROID_BUILD_HOST" "'$APKSIGNER' verify --print-certs ~/kubedroid/build/outputs/apk/release/$APK_NAME" 2>&1)" \
  || die "apksigner verify failed: $SIG"
echo "$SIG" | grep -q "$CERT_SHA256" || die "signing cert digest mismatch (expected spec 09 key)"
note "signature gate ok (cert digest matches)"

( cd "$OUT_DIR" && sha256sum "kubedroid-release-$VERSION.apk" > "SHA256SUMS-$VERSION" )
( cd "$OUT_DIR" && sha256sum -c "SHA256SUMS-$VERSION" ) >/dev/null || die "SHA256SUMS self-check failed"
note "checksums ok"

# ---- upload adapters -----------------------------------------------------
release_body() {
  cat <<EOF
kubedroid $VERSION — signed release.

Verify before installing:
    sha256sum -c SHA256SUMS-$VERSION
    apksigner verify --print-certs kubedroid-release-$VERSION.apk
Signing certificate SHA-256: $CERT_SHA256

Spec-driven changelog lives in docs/ (see
docs/10-release-hosting-and-public-repo.md for the publishing flow).
EOF
}

upload_gitea() {
  # Token goes into a 0600 curl config file (curl -K), never argv or logs.
  local cfg; cfg="$(mktemp)"; chmod 600 "$cfg"
  printf 'header = "Authorization: token %s"\n' "$KUBEDROID_RELEASE_UPLOAD_TOKEN" > "$cfg"
  local resp id
  resp="$(curl -sf -K "$cfg" -H 'Content-Type: application/json' \
    -X POST "$KUBEDROID_GITEA_URL/api/v1/repos/$REPO_SLUG/releases" \
    -d "{\"tag_name\":\"$VERSION\",\"name\":\"kubedroid $VERSION\",\"body\":$(printf '%s' "$(release_body)" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))'),\"prerelease\":false}")" \
    || { rm -f "$cfg"; die "release create failed"; }
  id="$(printf '%s' "$resp" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"
  for f in "kubedroid-release-$VERSION.apk" "SHA256SUMS-$VERSION"; do
    curl -sf -K "$cfg" -X POST \
      "$KUBEDROID_GITEA_URL/api/v1/repos/$REPO_SLUG/releases/$id/assets?name=$f" \
      -F "attachment=@$OUT_DIR/$f" >/dev/null || { rm -f "$cfg"; die "asset upload failed: $f"; }
  done
  rm -f "$cfg"
  note "gitea release $VERSION published (id $id)"
}

upload_github() {
  # GitHub Releases adapter (spec 10 stage 6). Token goes into a 0600 curl
  # config file (curl -K), never argv or logs.
  local api="https://api.github.com"
  local cfg; cfg="$(mktemp)"; chmod 600 "$cfg"
  printf 'header = "Authorization: Bearer ***"\n' "$KUBEDROID_RELEASE_UPLOAD_TOKEN" > "$cfg"
  local resp id upload_url
  resp="$(curl -sf -K "$cfg" -H 'Content-Type: application/json' \
    -X POST "$api/repos/$REPO_SLUG/releases" \
    -d "{\"tag_name\":\"$VERSION\",\"name\":\"kubedroid $VERSION\",\"body\":$(printf '%s' "$(release_body)" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))'),\"prerelease\":false,\"draft\":false}")" \
    || { rm -f "$cfg"; die "release create failed"; }
  id="$(printf '%s' "$resp" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"
  upload_url="$(printf '%s' "$resp" | python3 -c 'import json,sys;print(json.load(sys.stdin)["upload_url"].split("{")[0])')"
  for f in "kubedroid-release-$VERSION.apk" "SHA256SUMS-$VERSION"; do
    local ctype="application/octet-stream"
    [[ "$f" == *.apk ]] && ctype="application/vnd.android.package-archive"
    [[ "$f" == SHA256SUMS-* ]] && ctype="text/plain"
    curl -sf -K "$cfg" -H "Content-Type: $ctype" -X POST \
      "$upload_url?name=$f" \
      --data-binary "@$OUT_DIR/$f" >/dev/null || { rm -f "$cfg"; die "asset upload failed: $f"; }
  done
  # Read-back verification: release + both assets visible with non-zero size.
  curl -sf -K "$cfg" "$api/repos/$REPO_SLUG/releases/$id" \
    | python3 -c '
import json, sys
rel = json.load(sys.stdin)
want = {"kubedroid-release-'"$VERSION"'.apk", "SHA256SUMS-'"$VERSION"'"}
have = {a["name"]: a["size"] for a in rel.get("assets", [])}
missing = want - set(have)
empty = {n for n, s in have.items() if n in want and s == 0}
sys.exit(0 if not missing and not empty else 1)' \
    || { rm -f "$cfg"; die "release read-back verification failed"; }
  rm -f "$cfg"
  note "github release $VERSION published + read back ok (id $id)"
}

BACKEND="${KUBEDROID_RELEASE_BACKEND:-gitea}"
if [[ "$MODE" == "--dry-run" ]]; then
  note "DRY RUN — planned release:"
  note "  backend=$BACKEND tag=$VERSION"
  note "  assets: kubedroid-release-$VERSION.apk ($(du -h "$APK" | cut -f1)), SHA256SUMS-$VERSION"
  release_body | sed 's/^/  | /'
  note "all gates passed; re-run without --dry-run to publish"
  exit 0
fi

if [[ -z "${KUBEDROID_RELEASE_UPLOAD_TOKEN:-}" ]]; then die "token required (or use --dry-run)"; fi
if [[ "$BACKEND" == "gitea" && -z "${KUBEDROID_GITEA_URL:-}" ]]; then die "KUBEDROID_GITEA_URL required for the gitea backend"; fi
case "$BACKEND" in
  gitea)  upload_gitea ;;
  github) upload_github ;;
  *) die "unknown backend $BACKEND" ;;
esac
note "DONE: $VERSION published"
