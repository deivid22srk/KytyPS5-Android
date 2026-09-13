#!/usr/bin/env bash
# Build box64 (ARM64, Android/bionic) for the KytyPS5 Android port.
#
# Usage: scripts/android-build-box64.sh <repo-root> <android-ndk> <output-lib>
#
# Clones box64 at a pinned commit, applies the port's patches, builds with
# the NDK toolchain (ARM_DYNAREC on) and strips the result into a
# libbox64.so ready for jniLibs/arm64-v8a.
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
# patch: bionic fseeko64/ftello64 aliases + Android vulkan library name
git -C "$WORK/box64" apply --verbose "$ROOT/android/box64-patches/android-build.patch"

echo "[box64] configuring (NDK $NDK)"
cmake -S "$WORK/box64" -B "$WORK/build" -G Ninja \
	-DCMAKE_SYSTEM_NAME=Android \
	-DCMAKE_SYSTEM_VERSION=28 \
	-DCMAKE_ANDROID_NDK="$NDK" \
	-DCMAKE_ANDROID_ARCH_ABI=arm64-v8a \
	-DCMAKE_ANDROID_STL_TYPE=none \
	-DANDROID=ON \
	-DARM_DYNAREC=ON \
	-DCMAKE_BUILD_TYPE=RelWithDebInfo

echo "[box64] building"
cmake --build "$WORK/build" --parallel "$(nproc)"

"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" "$WORK/build/box64"
mkdir -p "$(dirname "$OUT")"
cp "$WORK/build/box64" "$OUT"
echo "[box64] done: $OUT ($(du -h "$OUT" | cut -f1))"
