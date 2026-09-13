#!/usr/bin/env bash
# Build the x86_64 kyty_emulator with the Android host-bridge SDL2 shim.
#
# Usage: scripts/android-build-emulator.sh <repo-root> <build-dir> <output-binary>
#
# Requires: clang/clang++ (or gcc), ninja, cmake, glslangValidator on PATH.
# Produces a stripped x86_64 Linux ELF that runs under box64 on Android.
set -euo pipefail

ROOT="${1:?repo root}"
BUILD="${2:?build dir}"
OUT="${3:?output binary}"

CC=clang CXX=clang++

echo "[emulator] configuring (KYTY_ANDROID_BRIDGE=ON)"
cmake -S "$ROOT" -B "$BUILD" -G Ninja \
	-DCMAKE_BUILD_TYPE=Release \
	-DCMAKE_C_COMPILER="$CC" \
	-DCMAKE_CXX_COMPILER="$CXX" \
	-DKYTY_BUILD_LAUNCHER=OFF \
	-DKYTY_ANDROID_BRIDGE=ON

echo "[emulator] building kyty_emulator"
cmake --build "$BUILD" --target kyty_emulator --parallel "$(nproc)"

strip "$BUILD/kyty_emulator"
mkdir -p "$(dirname "$OUT")"
cp "$BUILD/kyty_emulator" "$OUT"
echo "[emulator] done: $OUT ($(du -h "$OUT" | cut -f1))"

# smoke test: the binary must print its real usage banner
"$OUT" 2>&1 | head -1
