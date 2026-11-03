#!/bin/bash
# Container bootstrap: apt deps + the exact SDK packages. Runs BEFORE
# reprotest so the variation faketime never wraps a networked sdkmanager
# (the apt sdkmanager validates a transparency-log cert that fails under
# arbitrary clock offsets).
set -e
command -v reprotest >/dev/null 2>&1 || apt-get install -y -qq reprotest >/dev/null 2>&1 || {
  apt-get update -qq >/dev/null 2>&1
  apt-get install -y -qq python3-pip >/dev/null 2>&1
  python3 -m pip install --quiet --break-system-packages reprotest
}
apt-get update -qq >/dev/null 2>&1 || true
apt-get install -y -qq faketime libfaketime >/dev/null 2>&1 || true
if [ ! -d /opt/android-sdk/platforms/android-37.0 ] || [ ! -d /opt/android-sdk/build-tools/36.0.0 ]; then
  sdkmanager --sdk_root=/opt/android-sdk "platforms;android-37.0" "build-tools;36.0.0" < /dev/null >/dev/null
fi
echo BOOTSTRAP_OK
