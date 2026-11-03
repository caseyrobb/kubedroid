#!/usr/bin/env bash
# Companion to repro-docker.sh — executed ON the build host by that script.
# Never invoke directly. Runs two clean signed builds inside the F-Droid
# build-server image and compares SHA-256 of the produced APKs.
set -euo pipefail
IMAGE="${KUBEDROID_REPRO_IMAGE:?}"
NEGATIVE="${KUBEDROID_REPRO_NEGATIVE:-0}"
KS_PROPS="${KUBEDROID_KS_PROPS_HOST:?}"
KS_FILE="${KUBEDROID_KS_FILE_HOST:?}"
TREE="$HOME/kubedroid-repro"

docker run --rm \
  -v "$TREE":/work \
  -v "$KS_PROPS":/secrets/orig.properties:ro \
  -v "$KS_FILE":/secrets/release.keystore:ro \
  -e KUBEDROID_REPRO_NEGATIVE="$NEGATIVE" \
  "$IMAGE" bash -lc '
    set -euo pipefail
    cd /work/kubedroid
    grep -v "^storeFile=" /secrets/orig.properties > /secrets/keystore.properties
    echo "storeFile=/secrets/release.keystore" >> /secrets/keystore.properties
    export KUBEDROID_KEYSTORE_PROPERTIES=/secrets/keystore.properties
    # Licenses are pre-accepted in the image; no `yes` pipe (SIGPIPE on the
    # writer trips pipefail and kills the script silently).
    sdkmanager --sdk_root=/opt/android-sdk "platforms;android-37.0" "build-tools;36.0.0" < /dev/null
    build_leg() {
      rm -rf build
      ./gradlew --gradle-user-home /work/.gradle-agent :assembleRelease --console=plain -q
    }
    build_leg
    cp build/outputs/apk/release/*.apk /work/repro-A.apk
    if [ "${KUBEDROID_REPRO_NEGATIVE:-0}" = "1" ]; then
      # Negative control: perturbation that must change the output
      # (spec 11 stage 3 Testing). Never used for the gate itself.
      TARGET=$(find src/main/java -name "*.kt" | head -1)
      [ -n "$TARGET" ] || { echo "no sources to perturb"; exit 2; }
      echo "// repro perturbation $(date -u +%s%N)" >> "$TARGET"
    fi
    build_leg
    cp build/outputs/apk/release/*.apk /work/repro-B.apk
    HA=$(sha256sum /work/repro-A.apk | cut -d" " -f1)
    HB=$(sha256sum /work/repro-B.apk | cut -d" " -f1)
    echo "A=$HA"
    echo "B=$HB"
    # The container runs as root; hand root-owned build artifacts back so the
    # next rsync --delete from the wrapper can clean the tree.
    chown -R 1000:1000 /work 2>/dev/null || true
    if [ "$HA" = "$HB" ]; then echo REPRO_CHECK_PASS; else echo REPRO_CHECK_FAIL; exit 1; fi
  '
