#!/usr/bin/env bash
# Companion to reprotest-docker.sh — executed ON the build host. Never
# invoke directly. Stages a detached clone of the release ref plus signing
# material, then runs reprotest inside the F-Droid build-server image with a
# null virtual server. Expects env: KUBEDROID_KEYSTORE_PROPERTIES,
# KUBEDROID_KEYSTORE_FILE, KUBEDROID_REPRO_IMAGE, KUBEDROID_REPRO_CLONE_URL,
# KUBEDROID_REPRO_REF.
set -euo pipefail
SRC="$HOME/kubedroid-reprotest/src"
TOOLS="$HOME/kubedroid-reprotest/tools"

sudo rm -rf "$SRC"
mkdir -p "$SRC"
git clone -q --no-checkout "$KUBEDROID_REPRO_CLONE_URL" "$SRC"
git -C "$SRC" checkout -q --detach "$KUBEDROID_REPRO_REF"
# Keystore + properties staged INSIDE the source root: reprotest copies the
# whole tree (--source-pattern '*') into each build sandbox, so anything the
# build script needs must live beside it.
cp "$KUBEDROID_KEYSTORE_PROPERTIES" "$SRC/repro-keystore.properties"
cp "$KUBEDROID_KEYSTORE_FILE" "$SRC/repro.keystore"
python3 - "$SRC" <<'PY'
import sys, os
src = sys.argv[1]
p = os.path.join(src, "repro-keystore.properties")
lines = [l for l in open(p) if not l.startswith("storeFile=")]
lines.append("storeFile=%s/repro.keystore\n" % src)
open(p, "w").writelines(lines)
PY
cp "$TOOLS/reprotest-build.sh" "$SRC/build.sh"
chmod +x "$SRC/build.sh"
chown -R 1000:1000 "$SRC" 2>/dev/null || sudo chown -R 1000:1000 "$SRC"

# --privileged: reprotest's faketime/user-variation testbeds need it; the
# null virtual server runs builds directly in the container.
docker run --rm --privileged \
  -v "$SRC":/work -v "$TOOLS":/tools:ro \
  "$KUBEDROID_REPRO_IMAGE" bash -lc '
    set -e
    bash /tools/reprotest-sdk-bootstrap.sh
    cd /work
    reprotest --verbosity 3 \
      --variations=+domain_host,+user_group,+time,+build_path,+home,+umask,+environment \
      --no-diffoscope --source-pattern "*" -s /work "sh build.sh" "out.apk" 2>&1 | tail -200
    echo REPROTEST_DONE
    sha256sum /work/out*.apk 2>/dev/null || true
    chown -R 1000:1000 /work 2>/dev/null || true
  '
