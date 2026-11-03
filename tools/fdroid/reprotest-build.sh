#!/bin/bash
# reprotest build script — runs INSIDE the fdroid buildserver container,
# from a reprotest-copied copy of the checkout root. All signing material
# is staged beside this script (repro.keystore / repro-keystore.properties)
# by reprotest-docker-impl.sh.
set -euo pipefail
cd "$(dirname "$0")"
grep -v '^storeFile=' repro-keystore.properties > ks.properties
echo "storeFile=$PWD/repro.keystore" >> ks.properties
export KUBEDROID_KEYSTORE_PROPERTIES="$PWD/ks.properties"
# SDK packages are pre-installed by the container bootstrap; re-running the
# apt sdkmanager under reprotest's faketime breaks its transparency-log TLS
# cert validation, so only install if genuinely absent.
if [ ! -d /opt/android-sdk/platforms/android-37.0 ] || [ ! -d /opt/android-sdk/build-tools/36.0.0 ]; then
  sdkmanager --sdk_root=/opt/android-sdk "platforms;android-37.0" "build-tools;36.0.0" < /dev/null >/dev/null
fi
./gradlew --gradle-user-home /tmp/gradle-home :assembleRelease --console=plain -q
cp build/outputs/apk/release/*.apk "$PWD/out.apk"
