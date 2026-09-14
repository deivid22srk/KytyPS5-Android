#!/usr/bin/env bash
# Build the runtime skeleton for the KytyPS5 Android port.
#
# Usage: scripts/android-make-rootfs.sh <output-tar>
#
# The guest emulator is an x86_64-linux-android (bionic) binary: all its
# dynamic dependencies are the device's own bionic system libraries, which
# box64 wraps in-process. No glibc runtime is needed (the old Debian amd64
# rootfs existed only for the glibc guest and caused fatal ABI clashes —
# see docs/PORTING.md).
#
# What the emulator still expects on disk at runtime:
#   - a writable skeleton under KYTY_BASE_PATH (created by RuntimeInstaller
#     from this tar): etc/ marker, the sandbox data/temp dirs are created
#     by the session itself;
#   - HOME/TMPDIR point inside the app's files dir (set by EmulatorSession).
#
# NOTE: the asset must be a PLAIN tar. aapt2 transparently gunzips assets
# whose name ends in ".gz" and strips the suffix, so a rootfs.tar.gz would
# ship as rootfs.tar in the APK and the installer's asset name would not
# match.
set -euo pipefail

OUT="${1:?output tar}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/kyty-rootfs.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

echo "[rootfs] building bionic-guest runtime skeleton"
mkdir -p "$WORK/rootfs/etc"
# A version marker plus an mtab alias — the emulator's file-system sandbox
# checks for their existence on some paths.
echo "kyty-android-bionic" > "$WORK/rootfs/etc/kyty-release"
printf 'KytyPS5-Android runtime skeleton (bionic x86_64 guest, box64)\n' > "$WORK/rootfs/etc/kyty-release.txt"

echo "[rootfs] packing"
mkdir -p "$(dirname "$OUT")"
tar -cf "$OUT" -C "$WORK/rootfs" .

echo "[rootfs] done: $OUT ($(du -h "$OUT" | cut -f1))"
# note: plain `tar -tf | head` breaks under `set -o pipefail` (SIGPIPE)
tar -tf "$OUT" > /tmp/kyty-rootfs-list.txt
head -5 /tmp/kyty-rootfs-list.txt
rm -f /tmp/kyty-rootfs-list.txt
