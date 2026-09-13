#!/usr/bin/env bash
# Build box64 (ARM64, Android/bionic) for the KytyPS5 Android port.
#
# Usage: scripts/android-build-box64.sh <repo-root> <android-ndk> <output-lib>
#
# Clones box64 at a pinned commit, applies the port's patches and builds
# libbox64.so (SHARED library, ARM_DYNAREC on) for in-process use: the host
# app dlopen()s it and calls box64_main() so the x86_64 emulator runs inside
# the app process (ANativeWindow pointers stay valid; guest exit() is
# bridged back to the host via longjmp).
set -euo pipefail

ROOT="${1:?repo root}"
NDK="${2:?ndk path}"
OUT="${3:?output .so path}"

BOX64_COMMIT="eb49d3d97f3a21844f903d99dca80dec7add36bd"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/box64-build.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

echo "[box64] cloning @ ${BOX64_COMMIT}"
git clone --quiet --filter=blob:none "https://github.com/ptitseb/box64" "$WORK/box64"
git -C "$WORK/box64" checkout --quiet "$BOX64_COMMIT"

echo "[box64] applying android patches"
git -C "$WORK/box64" apply --verbose "$ROOT/android/box64-patches/android-build.patch"

echo "[box64] configuring (NDK $NDK)"
cmake -S "$WORK/box64" -B "$WORK/build" -G Ninja \
        -DCMAKE_SYSTEM_NAME=Android \
        -DCMAKE_SYSTEM_VERSION=28 \
        -DCMAKE_ANDROID_NDK="$NDK" \
        -DCMAKE_ANDROID_ARCH_ABI=arm64-v8a \
        -DCMAKE_ANDROID_STL_TYPE=none \
        -DANDROID=ON \
        -DNOBOX64=ON \
        -DARM_DYNAREC=ON \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DCMAKE_BUILD_TYPE=RelWithDebInfo

echo "[box64] building libbox64.so"
cmake --build "$WORK/build" --parallel "$(nproc)"

LIB="$WORK/build/libbox64.so"
test -f "$LIB" || { echo "ERROR: libbox64.so not produced" >&2; exit 1; }

# verify the library entry point is exported
# (note: `nm | grep -q` breaks under pipefail — grep -q closes the pipe
# early and nm dies of SIGPIPE; grep without -q drains all input instead)
if ! "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" -D "$LIB" | grep "box64_main" > /dev/null; then
        echo "ERROR: box64_main not exported from libbox64.so" >&2
        exit 1
fi

"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" "$LIB"
mkdir -p "$(dirname "$OUT")"
cp "$LIB" "$OUT"
echo "[box64] done: $OUT ($(du -h "$OUT" | cut -f1))"
