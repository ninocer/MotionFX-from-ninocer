#!/usr/bin/env bash
# Source in the cloud shell. Windows users only need GitHub Actions.
export MOTIONFX_TOOLCHAINS="${MOTIONFX_TOOLCHAINS:-/workspace/toolchains}"
export JAVA_HOME="$MOTIONFX_TOOLCHAINS/jdk-17"
export ANDROID_HOME="$MOTIONFX_TOOLCHAINS/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$MOTIONFX_TOOLCHAINS/android-user"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
unset ANDROID_PREFS_ROOT
export GRADLE_USER_HOME="$MOTIONFX_TOOLCHAINS/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
if [ -n "${HTTPS_PROXY:-}" ]; then
    motionfx_proxy_options=$(python3 -c 'import os,urllib.parse; p=urllib.parse.urlparse(os.environ["HTTPS_PROXY"]); print("-Dhttps.proxyHost="+p.hostname+" -Dhttps.proxyPort="+str(p.port or 80)+" -Dhttp.proxyHost="+p.hostname+" -Dhttp.proxyPort="+str(p.port or 80))')
    export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} $motionfx_proxy_options"
    if [ -f /etc/ssl/certs/java/cacerts ]; then
        export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
    fi
    unset motionfx_proxy_options
fi
