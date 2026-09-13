# KytyPS5 Android Port — Technical Report

This document records the engineering analysis behind the Android port of
KytyPS5: how the emulator executes on ARM64 phones, why the binary
translation layer was chosen, and what each component does.

## 1. Execution model

KytyPS5 is an **HLE emulator**: PS5 kernel services and libraries are
re-implemented natively, and the game's x86_64 code executes **natively,
in-process**. Two properties follow from the upstream design:

1. The emulator binary itself is plain x86_64 Linux code (the project builds
   for Windows/Linux/macOS with CMake; macOS runs the x86_64 build under
   Rosetta 2 on Apple Silicon — the upstream-supported precedent for
   whole-process translation).
2. Game code runs as *host* code inside the emulator process: the loader
   maps executable pages, patches trampolines (Zydis decode + xbyak emit on
   Windows; the SysV red zone is honored as-is on Linux) and jumps into it.
   There is no separate CPU interpreter.

Consequence for Android: **the entire x86_64 emulator process — not just
game code — must run under an x86_64→ARM64 translation layer**, exactly like
Rosetta 2 does on macOS. This is the same architecture proven in production
by Winlator (box64 + Wine for running x86 Windows games on Android).

## 2. Binary translator evaluation (box64 vs FEX-Emu)

| Criterion | box64 | FEX-Emu |
|---|---|---|
| Android/bionic builds | First-class: `ANDROID`/`TERMUX` CMake options, used by Winlator, Termux and RetroBox distributions | Experimental; primarily targets glibc Linux distros and needs a rootfs with `ptrace` or a custom ThunkLib host setup |
| Whole-process translation | Designed for it (runs ELF x86_64 end-to-end, `exec()` from an ARM64 parent) | Designed for it, but Android deployments are unproven at scale |
| Vulkan pass-through | Ships a maintained `wrappedvulkan` that forwards x86_64 Vulkan calls to the host ARM64 loader — **required** for the emulator's Vulkan 1.3 renderer | No equivalent Android-proven Vulkan wrapper |
| Dynarec quality on ARM64 | Mature ARM64 dynarec (big-block, strongmem, safe flags options) tuned over years on Adreno/Mali-class hardware | Excellent dynarec, but the Android integration cost dominates |
| Process model on Android | Runs as a normal app child process via `fork`/`execv` from JNI — no root, no ptrace | Rootfs/ptrace-centric setup is hostile to the Android app sandbox |
| Footprint | ~20 MB stripped shared-object build | Larger setup surface (rootfs + config generation steps) |

**Decision: box64.** The decisive factors are (a) the Android/bionic build
path is already upstream and battle-tested by Winlator, (b) the wrapped
Vulkan library is exactly the integration point the renderer needs, and
(c) it runs whole x86_64 processes inside the normal app sandbox with zero
root requirements. FEX-Emu is a strong translator on glibc Linux, but on
Android its integration risks (ptrace/rootfs assumptions, no maintained
Vulkan wrapper) would have added risk without adding capability.

## 3. Architecture

The x86_64 emulator runs **inside the app process** (Winlator-style):
`libkytyhost.so` dlopen()s `libbox64.so` (a patched box64 built as a shared
library) and calls `box64_main()` on a dedicated pthread. Since there is no
`exec()`, the address space — including the `ANativeWindow*` published
through the bridge — stays valid, which is what makes
`vkCreateAndroidSurfaceKHR` from the translated guest correct. A box64 patch
bridges the guest's `exit()` back to the host via `longjmp`, so a guest
error path ends the *session* instead of killing the app.

```
┌─────────────────────────── one ARM64 app process ─────────────────────────┐
│  Kotlin/Compose M3 UI                                                     │
│    library (param.sfo), settings→CLI, logs, SAF import, gamepads          │
│  libkytyhost.so (this port)                                               │
│    dlopen(libbox64.so) + box64_main() on a pthread                        │
│    ANativeWindow publish · AAudio sinks · input inject · vibrator rumble  │
│    shared-memory bridge (events in, PCM out, rumble back)                 │
│  libbox64.so (ARM64 dynarec, in-process)                                  │
│    translates the x86_64 emulator + game code below                       │
│  kyty_emulator (unmodified upstream core + android/bridge SDL2 shim)      │
│    ELF loader · HLE kernel/libs · RDNA2→SPIR-V shader recompiler          │
│    Vulkan 1.3 host renderer ──► box64 wrappedvulkan ──► ARM64 driver      │
│    shim: window/events/audio/gamecontroller  ──► shared-memory bridge     │
└───────────────────────────────────────────────────────────────────────────┘
```

Key components:

- **`android/bridge/kyty_bridge.h`** — neutral binary protocol shared by
  both sides (identical layout on x86_64 and aarch64; fixed-width fields,
  SPSC rings, acquire/release ordering).
- **`android/bridge/sdl2_shim/`** — drop-in SDL2 replacement compiled into
  the x86_64 emulator (`KYTY_ANDROID_BRIDGE=ON` swaps `SDL2-static` for it).
  Implements exactly the SDL2 surface the emulator uses (the symbol set was
  extracted from the sources; the link fails closed if anything is missing).
  - `SDL_Vulkan_CreateSurface` → waits for the host-published `ANativeWindow*`
    and calls `vkCreateAndroidSurfaceKHR` (the pointer crosses the boundary
    unchanged: it is a real host pointer used only by host Vulkan).
  - Events: host ring → `SDL_Event` translation (keyboard, mouse, touch,
    controllers, window/lifecycle, orientation).
  - Audio: queued-mode devices forwarded to per-device PCM rings; the
    obtained spec echoes the desired spec because the host AAudio stream is
    configured with the guest-requested (freq, channels, format).
- **`app/src/main/cpp/`** (`libkytyhost.so`, ARM64) — owns the real Android
  resources: `ANativeWindow` (from the Compose-embedded `SurfaceView`),
  AAudio output streams (one per guest audio device, data-callback driven),
  the box64 child process (real `fork`/`execv`, stdout/stderr streamed to
  the UI, real exit code via `waitpid`), vibrator haptics for rumble, and
  Vulkan physical-device enumeration for the settings screen.
- **box64** — built from the pinned upstream commit plus
  `android/box64-patches/android-build.patch` (bionic `fseeko64` aliases;
  Android Vulkan library name; a shared-library build with `box64_main()`;
  the guest-`exit()` longjmp bridge). Installed as
  `jniLibs/arm64-v8a/libbox64.so` and dlopen()ed from
  `nativeLibraryDir` (uncompressed, `useLegacyPackaging=false`).
- **Runtime rootfs** — minimal Debian amd64 set (libc6, libstdc++6,
  libgcc-s1, zlib1g, libbz2-1.0, liblzma5) shipped as an APK asset and
  extracted on first run; the emulator's dynamic loader and libraries come
  from it via `BOX64_LD_LIBRARY_PATH`.

## 4. What is real

Every user-visible feature maps to a real mechanism:

| UI element | Real backend |
|---|---|
| Game library (title, id, version, size) | `param.sfo` parser over the imported game tree |
| Import game | SAF document-tree copy into app storage; rejects folders without `eboot.bin` |
| Settings | 1:1 mapping to `kyty_emulator` CLI flags and `BOX64_*` environment variables |
| GPU device list | live `vkEnumeratePhysicalDevices` on the phone |
| Play | `fork`/`execv` box64 with the real argv/env; ANativeWindow published to the guest |
| Virtual DualSense | a real bridge pad, same code path as physical controllers |
| Physical gamepads | `InputDevice` → bridge pads (axes, buttons, triggers, dpad) |
| Logs overlay | live stdout/stderr of the child process |
| Exit | `SDL_QUIT` + `SIGTERM`; exit code surfaced from `waitpid` |
| Rumble | rumble ring → `VibratorManager` |

## 5. Known limits (honest engineering statement)

- Performance is bounded by box64's dynarec on mobile ARM64 (Snapdragon
  8-class devices are the realistic floor); Vulkan 1.3 with the required
  features is mandatory on the phone GPU (Adreno 7xx/8xx, Mali-G7xx class).
- Compatibility equals upstream KytyPS5 (early-development stage: 2D titles
  and a set of UE4/5/Unity games boot in-game on PC); regressions or
  improvements come from upstream, which this port tracks.
- **Single session per process**: box64's library mode is not re-entrant,
  so after a session ends the app offers a one-tap restart before another
  game can be launched.
- **Hard guest crashes** (a translated SIGSEGV the emulator does not catch,
  or a guest `_exit()`) take the whole app down, because the emulator runs
  in-process; logcat carries the details. Normal `exit()` paths are bridged
  back as a session end with the real exit code.
- Guest stdio is captured by redirecting the process stdout/stderr to a log
  file (the emulator logs heavily to stdout); ART's own stdout use is
  minimal, but the log also contains box64 diagnostics.
- No DualSense adaptive-trigger haptics on phones (degraded to rumble), no
  LED, no gyro yet (bridge protocol reserves the paths).
- Games are imported into internal storage (`filesDir`); external-storage
  libraries are not supported yet.

### CI regression net

`tests/bridge/bridge_protocol_test.cpp` links the *real* SDL2 shim and
drives it against a host-side bridge: guest attachment (shared-memory
visibility), event delivery, queued-audio PCM roundtrip, rumble roundtrip
and shutdown. It runs natively on the CI runner before any APK step — the
class of bug where the guest reads a private snapshot instead of the shared
mapping fails this test immediately.
