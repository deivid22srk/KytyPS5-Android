#!/usr/bin/env bash
# Build the minimal Debian/Ubuntu x86_64 rootfs for the KytyPS5 Android port.
#
# Usage: scripts/android-make-rootfs.sh <output-tar.gz>
#
# The x86_64 kyty_emulator links against: libc, libm, libstdc++, libgcc_s,
# libz, libbz2, liblzma and the dynamic loader. We pull exactly those
# packages for the build host's native arch (amd64) with `apt-get download`
# (no root required), unpack them and produce a tarball consumed as an APK
# asset. The guest never executes system binaries from here — box64 loads
# only these shared libraries.
set -euo pipefail

OUT="${1:?output tar.gz}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/kyty-rootfs.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

echo "[rootfs] downloading amd64 packages (host glibc must match emulator build)"
cd "$WORK"
apt-get download \
        libc6 \
        libstdc++6 \
        libgcc-s1 \
        zlib1g \
        libbz2-1.0 \
        liblzma5

echo "[rootfs] extracting"
mkdir -p rootfs
for deb in *.deb; do
        dpkg-deb -x "$deb" rootfs/
done

# sanity: dynamic loader must exist
if [ ! -e rootfs/lib64/ld-linux-x86-64.so.2 ] &&
        [ ! -e rootfs/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 ]; then
        echo "ERROR: ld-linux-x86-64.so.2 not found in extracted rootfs" >&2
        exit 1
fi

echo "[rootfs] packing"
mkdir -p "$(dirname "$OUT")"
tar -czf "$OUT" -C rootfs .

echo "[rootfs] done: $OUT ($(du -h "$OUT" | cut -f1))"
# note: plain `tar -tzf | head` breaks under `set -o pipefail` (SIGPIPE)
tar -tzf "$OUT" > /tmp/kyty-rootfs-list.txt
head -5 /tmp/kyty-rootfs-list.txt
rm -f /tmp/kyty-rootfs-list.txt
