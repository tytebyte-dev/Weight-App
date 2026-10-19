#!/usr/bin/env bash
# Builds the two native cores the app embeds and drops them where Gradle picks them up:
#
#   * Xray-core 26.3.27  (native/xray-core, wrapped by native/xraybridge)  -> gomobile AAR
#       classes.jar -> smarttubetv/libs/xraycore.jar
#       jni/<abi>/libgojni.so -> smarttubetv/src/stmobile/jniLibs/<abi>/
#   * hev-socks5-tunnel 2.19.0 (native/hev-socks5-tunnel)                   -> ndk-build
#       libhev-socks5-tunnel.so -> smarttubetv/src/stmobile/jniLibs/<abi>/
#
# The outputs are build artefacts (git-ignored). Without them the app still builds and runs;
# the bypass feature then reports "core not built" instead of starting.
#
# Needs: Go (go.mod says 1.26; with GOTOOLCHAIN=auto an older Go downloads it), an Android NDK
# (r27+ recommended: 16 KB page alignment), network access to the Go module proxy.
#
#   ANDROID_NDK_HOME=/path/to/ndk ./native/build-cores.sh [abi ...]   # default: all four ABIs
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
OUT_LIBS="$ROOT/smarttubetv/libs"
OUT_JNI="$ROOT/smarttubetv/src/stmobile/jniLibs"
API=24   # app minSdk

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "error: set ANDROID_NDK_HOME to an installed NDK (r27+ recommended)" >&2
  exit 1
fi
export ANDROID_NDK_HOME="$NDK"

ABIS=("$@")
[[ ${#ABIS[@]} -eq 0 ]] && ABIS=(armeabi-v7a arm64-v8a x86 x86_64)

go_targets=()
for abi in "${ABIS[@]}"; do
  case "$abi" in
    armeabi-v7a) go_targets+=(android/arm) ;;
    arm64-v8a)   go_targets+=(android/arm64) ;;
    x86)         go_targets+=(android/386) ;;
    x86_64)      go_targets+=(android/amd64) ;;
    *) echo "error: unknown ABI $abi" >&2; exit 1 ;;
  esac
done
TARGETS="$(IFS=,; echo "${go_targets[*]}")"

mkdir -p "$OUT_LIBS" "$OUT_JNI"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> Xray-core via gomobile ($TARGETS)"
export GOFLAGS="${GOFLAGS:-} -mod=mod"
export GOTOOLCHAIN="${GOTOOLCHAIN:-auto}"
export GOBIN="$WORK/bin"
export PATH="$GOBIN:$PATH"
(
  cd "$HERE/xraybridge"
  go install golang.org/x/mobile/cmd/gomobile@latest
  go install golang.org/x/mobile/cmd/gobind@latest
  go get golang.org/x/mobile/bind@latest
  go mod tidy
  gomobile init
  gomobile bind -v -trimpath \
    -target="$TARGETS" -androidapi "$API" \
    -ldflags="-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384" \
    -o "$WORK/xraycore.aar" .
)
mkdir -p "$WORK/aar"
unzip -q -o "$WORK/xraycore.aar" -d "$WORK/aar"
cp "$WORK/aar/classes.jar" "$OUT_LIBS/xraycore.jar"
for abi in "${ABIS[@]}"; do
  mkdir -p "$OUT_JNI/$abi"
  cp "$WORK/aar/jni/$abi/libgojni.so" "$OUT_JNI/$abi/libgojni.so"
done

echo "==> hev-socks5-tunnel via ndk-build"
HEV="$HERE/hev-socks5-tunnel"
mkdir -p "$WORK/hev"
"$NDK/ndk-build" -j"$(nproc 2>/dev/null || echo 4)" \
  NDK_PROJECT_PATH="$WORK/hev" \
  APP_BUILD_SCRIPT="$HEV/Android.mk" \
  NDK_APPLICATION_MK="$HEV/Application.mk" \
  NDK_LIBS_OUT="$WORK/hev/libs" \
  NDK_OUT="$WORK/hev/obj" \
  APP_ABI="${ABIS[*]}" \
  APP_PLATFORM="android-$API" \
  REV_ID="2.19.0"
for abi in "${ABIS[@]}"; do
  mkdir -p "$OUT_JNI/$abi"
  cp "$WORK/hev/libs/$abi/libhev-socks5-tunnel.so" "$OUT_JNI/$abi/libhev-socks5-tunnel.so"
done

echo "==> done"
ls -l "$OUT_LIBS"/xraycore.jar "$OUT_JNI"/*/*.so
