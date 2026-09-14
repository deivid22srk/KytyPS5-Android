#!/usr/bin/env bash
# Build and run the bridge protocol integration test natively (x86_64 host).
#
# Links the real SDL2 shim sources against a host-side bridge writer and
# asserts the full roundtrip: guest attachment (shared-memory visibility),
# event delivery, queued-audio PCM transfer, rumble and shutdown.
set -euo pipefail

ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/kyty-bridge-test.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT

# NOTE: -DSDL_MAIN_HANDLED is host-test only (some SDL builds rename
# main->SDL_main, which breaks the test link); the guest shim itself
# compiles without this flag.
g++ -std=c++20 -O1 -g \
	-DSDL_MAIN_HANDLED \
	-I "$ROOT/3rdparty/SDL2/include" \
	-I "$ROOT/3rdparty/Vulkan-Headers/include" \
	-I "$ROOT/android/bridge" \
	"$ROOT/android/bridge/sdl2_shim/sdl2_shim.cpp" \
	"$ROOT/android/bridge/sdl2_shim/sdl2_shim_vulkan.cpp" \
	"$ROOT/android/bridge/sdl2_shim/sdl2_shim_audio.cpp" \
	"$ROOT/android/bridge/sdl2_shim/sdl2_shim_gamecontroller.cpp" \
	"$ROOT/android/bridge/sdl2_shim/sdl2_shim_keys.cpp" \
	"$ROOT/tests/bridge/bridge_protocol_test.cpp" \
	-o "$OUT/bridge_test" -pthread -ldl

"$OUT/bridge_test"
echo "[bridge-test] OK"
