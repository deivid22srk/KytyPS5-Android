package dev.kytyps5.android.emu

import dev.kytyps5.android.settings.EmuSettings
import java.io.File

/**
 * Builder for the box64/guest session environment.
 *
 * This file imports no android.* APIs and only touches EmuSettings'
 * platform-independent members (`toBox64Env`, `box64Log`), so host-JVM
 * unit tests compile it with minimal stubs for the class reference
 * (see SessionEnvTest). Upstream box64 variable reference: docs/USAGE.md —
 * list variables are `:`-separated (`XXXX:YYYY:ZZZZ`), and guest libraries
 * are located through `BOX64_LD_LIBRARY_PATH` (there is no `BOX64_ROOTFS`
 * variable upstream; box64 does not chroot).
 */
object SessionEnv {

    /** Guest requests `libvulkan.so.1`; the Android box64 build names the
     *  wrapped module `libvulkan.so` — list both (colon-separated, as
     *  upstream requires) so the wrapper matches either SONAME. */
    const val EMULATED_LIBS = "libvulkan.so.1:libvulkan.so"

    fun build(
        kytyRoot: File,
        rootfsDir: File,
        emulatorBinary: File,
        bridgeShm: File,
        settings: EmuSettings,
    ): List<Pair<String, String>> {
        val env = mutableListOf<Pair<String, String>>()

        // Translator preferences first; mandatory session keys below take
        // precedence (native setenv is last-wins, so no key may appear twice
        // with different values — BOX64_LOG is handled conditionally).
        settings.toBox64Env().forEach { (k, v) -> env.add(k to v) }

        // box64 library discovery inside the Debian sysroot.
        env.add("BOX64_PATH" to "${kytyRoot}/bin:${rootfsDir}/usr/bin:${rootfsDir}/bin")
        env.add(
            "BOX64_LD_LIBRARY_PATH" to
                "${rootfsDir}/usr/lib/x86_64-linux-gnu:${rootfsDir}/lib/x86_64-linux-gnu:" +
                "${rootfsDir}/lib64:${rootfsDir}/lib:${rootfsDir}/usr/lib",
        )
        env.add("BOX64_EMULATED_LIBS" to EMULATED_LIBS)
        // Crash black-box: stdout is a log file (not a tty), where box64's
        // default log level NONE would also silence its own SIGSEGV report.
        // These three only cost anything when a guest signal is actually
        // caught, so they stay forced on every session.
        env.add("BOX64_SHOWSEGV" to "1")
        env.add("BOX64_SHOWBT" to "1")
        env.add("BOX64_ROLLING_LOG" to "512")
        if (settings.box64Log == 0) {
            // INFO load/bind trace; startup-bounded (not per-frame).
            env.add("BOX64_LOG" to "1")
        }
        // Bridge.
        env.add("KYTY_BRIDGE_SHM" to bridgeShm.absolutePath)
        // Directory of the emulator binary (SDL_GetBasePath fallback).
        // NOTE: File(parent, "/") resolves to "/" on Unix (an absolute
        // child drops the parent), so use the parent path directly.
        val basePath = emulatorBinary.parentFile?.absolutePath
            ?: File(kytyRoot, "bin").absolutePath
        env.add("KYTY_BASE_PATH" to basePath)
        // Misc unix env.
        env.add("HOME" to "${kytyRoot}/data")
        env.add("TMPDIR" to "${kytyRoot}/data/tmp")
        env.add("PATH" to "${rootfsDir}/usr/bin:${rootfsDir}/bin:/system/bin")
        env.add("LANG" to "C.UTF-8")
        env.add("LC_ALL" to "C.UTF-8")
        return env
    }
}
