#!/usr/bin/env bash
# Build the kyty_emulator as an x86_64-linux-android (bionic) guest binary.
#
# Usage: scripts/android-build-emulator.sh <repo-root> <build-dir> <output-binary> <android-ndk>
#
# The guest runs under box64 (library mode) inside the ARM64 host app. It is
# compiled against Android/bionic — NOT glibc — so that its dynamic
# dependencies are exactly the bionic system libraries box64 knows how to
# wrap against the host process (libc.so, libm.so, ...). A glibc guest would
# pull emulated glibc libraries (ld-linux, libstdc++, ...) whose
# relocations/init depend on real rtld state that does not exist under
# box64, crashing during startup (see the porting notes in docs/PORTING.md).
#
# Toolchain: the NDK's x86_64-linux-android clang wrappers (API 28).
# libc++ is linked statically; the process entry is android/guest/guest_entry.c
# (-nostartfiles) instead of bionic crt (which would call __libc_init).
#
# Requires: ninja, cmake, glslangValidator on PATH, and the NDK.
set -euo pipefail

ROOT="${1:?repo root}"
BUILD="${2:?build dir}"
OUT="${3:?output binary}"
NDK="${4:?android ndk path}"

API="${KYTY_ANDROID_API:-28}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TC/bin/x86_64-linux-android${API}-clang"
CXX="$TC/bin/x86_64-linux-android${API}-clang++"

for tool in "$CC" "$CXX"; do
        test -x "$tool" || { echo "ERROR: missing NDK toolchain: $tool" >&2; exit 1; }
done

echo "[emulator] configuring (bionic x86_64 guest, KYTY_ANDROID_BRIDGE=ON)"
cmake -S "$ROOT" -B "$BUILD" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_COMPILER="$CC" \
        -DCMAKE_CXX_COMPILER="$CXX" \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DKYTY_BUILD_LAUNCHER=OFF \
        -DKYTY_ANDROID_BRIDGE=ON

echo "[emulator] building kyty_emulator"
cmake --build "$BUILD" --target kyty_emulator --parallel "$(nproc)"

BIN="$BUILD/kyty_emulator"
test -f "$BIN" || { echo "ERROR: kyty_emulator not produced" >&2; exit 1; }

echo "[emulator] guest sanity checks"
# 1. The binary must be an x86_64 PIE with no interpreter-side glibc deps.
file "$BIN" | grep -q "ELF 64-bit LSB pie executable, x86-64" \
        || { echo "ERROR: unexpected binary format: $(file "$BIN")" >&2; exit 1; }
# 2. Its DT_NEEDED must only contain bionic system libraries that box64 wraps.
NEEDED="$(readelf -dW "$BIN" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p')"
echo "[emulator] DT_NEEDED: $(echo "$NEEDED" | tr '\n' ' ')"
BAD="$(echo "$NEEDED" | grep -Ev '^(libc\.so|libm\.so|libdl\.so|libz\.so|libvulkan\.so|liblog\.so|libandroid\.so)$' || true)"
if [ -n "$BAD" ]; then
        echo "ERROR: guest must only depend on bionic system libraries; unexpected: $BAD" >&2
        exit 1
fi
# (undefined imports are fine: the linker already fails on anything the bionic
#  sysroot cannot satisfy, and the glibc compat shims are link-time objects)

"$TC/bin/llvm-strip" "$BIN"
mkdir -p "$(dirname "$OUT")"
cp "$BIN" "$OUT"
echo "[emulator] done: $OUT ($(du -h "$OUT" | cut -f1))"

# smoke test: the binary must print its real usage banner (run under a
# native x86_64 host when available; on ARM64 CI runners this is a no-op).
if uname -m | grep -q x86_64 && command -v box64 >/dev/null 2>&1; then
        "$OUT" 2>&1 | head -1
fi
