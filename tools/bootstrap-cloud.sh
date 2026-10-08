#!/usr/bin/env bash
set -euo pipefail
motionfx_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
. "$motionfx_root/tools/cloud-env.sh"
mkdir -p "$MOTIONFX_TOOLCHAINS/downloads" "$ANDROID_HOME" "$ANDROID_USER_HOME"

download_verified() {
    local url="$1" destination="$2" expected="$3"
    if [ ! -f "$destination" ]; then
        curl --fail --location --silent --show-error --retry 3 "$url" -o "$destination.part"
        mv "$destination.part" "$destination"
    fi
    python3 - "$destination" "$expected" <<'PY'
import hashlib, pathlib, sys
p = pathlib.Path(sys.argv[1])
if hashlib.sha256(p.read_bytes()).hexdigest() != sys.argv[2]:
    raise SystemExit(f"Checksum mismatch: {p}. Remove the failed download and retry.")
PY
}

if [ ! -x "$JAVA_HOME/bin/javac" ]; then
    motionfx_jdk="$MOTIONFX_TOOLCHAINS/downloads/OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz"
    download_verified 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz' "$motionfx_jdk" '8682892fc02965930b9022c066fa164dd6f458ef4a5dc262016aa28333b30f49'
    mkdir -p "$JAVA_HOME"
    tar -xzf "$motionfx_jdk" --strip-components=1 -C "$JAVA_HOME"
fi
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    motionfx_sdk_zip="$MOTIONFX_TOOLCHAINS/downloads/android-tools.zip"
    download_verified 'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip' "$motionfx_sdk_zip" '2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258'
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    unzip -q "$motionfx_sdk_zip" -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi

# Avoid masking sdkmanager failures or treating yes's SIGPIPE as an SDK failure.
set +o pipefail
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null
motionfx_license_status=${PIPESTATUS[1]}
set -o pipefail
test "$motionfx_license_status" -eq 0
sdkmanager --sdk_root="$ANDROID_HOME" 'platform-tools' 'platforms;android-35' 'build-tools;34.0.0'
cd "$motionfx_root"
python3 tools/package_plugin.py examples/starter examples/starter.mfxplugin --check
python3 tools/package_plugin.py examples/starter app/src/main/assets/starter.mfxplugin --check
./gradlew --no-daemon --console=plain :plugin-api:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
