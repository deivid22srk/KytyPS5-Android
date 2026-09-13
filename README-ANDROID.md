# KytyPS5 for Android

A real Android port of [KytyPS5](https://github.com/KytyPS5/KytyPS5) (GPL-2.0),
the open-source PlayStation 5 emulator — powered by **box64** for x86_64 →
ARM64 binary translation, the same proven approach used by Winlator.

> [!IMPORTANT]
> This project is not affiliated with Sony Interactive Entertainment.
> No games or copyrighted system software are distributed. Use only legally
> obtained, decrypted game dumps.

## How it works

The upstream emulator core (CPU HLE, kernel, libraries, RDNA 2 → SPIR-V
shader recompiler, Vulkan 1.3 renderer) is compiled **unchanged** as an
x86_64 Linux binary and executed under box64's ARM64 dynarec. A small SDL2
compatibility shim inside the emulator forwards window, input and audio to
an ARM64 host library through a shared-memory bridge; the host renders into
the app's `SurfaceView` via `vkCreateAndroidSurfaceKHR` (Adreno/Mali
drivers), plays audio through AAudio, and feeds touch/virtual/physical
gamepad input back to the guest.

Full engineering report: [`docs/PORTING.md`](docs/PORTING.md).

## Building

The GitHub Actions workflow [`.github/workflows/build.yml`](.github/workflows/build.yml)
performs the complete build (no local Android setup required):

1. builds box64 (ARM64, Android, dynarec) with the NDK,
2. builds the x86_64 emulator with the Android bridge (`KYTY_ANDROID_BRIDGE=ON`),
3. packages a minimal Debian amd64 rootfs,
4. produces debug and release APKs with everything embedded,
5. uploads the APKs as workflow artifacts.

Manual build on a Linux host with Android SDK/NDK installed:

```bash
./gradlew assembleDebug   # after running the three scripts/android-*.sh
```

## Using the app

1. Install the debug APK (artifact of the build workflow) on a
   **Vulkan 1.3 capable** ARM64 device (Android 9/API 28+, 8 GB RAM class).
2. Import a legally owned, **decrypted** PS5 game dump folder (must contain
   `eboot.bin`; metadata is read from the real `sce_sys/param.sfo`).
3. Adjust settings if needed — every switch maps to a real emulator CLI
   flag or box64 environment variable.
4. Tap play; the on-screen log overlay shows the actual emulator output,
   including real loader and GPU errors.

## Repository layout

```
android/bridge/          shared-memory protocol + SDL2 shim (x86_64 side)
android/box64-patches/   patches applied to the pinned box64 commit
app/                     Android app (Kotlin/Compose M3 + ARM64 host lib)
scripts/                 box64 / emulator / rootfs build scripts
docs/PORTING.md          technical report (box64 vs FEX-Emu evaluation)
.github/workflows/       CI (complete APK build)
```

## Licenses

- KytyPS5 emulator: **GPL-2.0** (see `LICENSE`).
- box64 (pinned commit + patches): **MIT** (upstream license).
- This port's Android glue code: **GPL-2.0** (derived work).
