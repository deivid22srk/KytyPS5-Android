package dev.kytyps5.android.emu

import dev.kytyps5.android.settings.EmuSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Host-JVM tests for the box64/guest session environment.
 *
 * Regression background: two real launch bugs lived in this table —
 * `BOX64_EMULATED_LIBS` was once comma-separated (upstream requires
 * `XXXX:YYYY:ZZZZ`), and `KYTY_BASE_PATH` was built with
 * `File(parent, "/")`, which resolves to `/` on Unix because an absolute
 * child drops the parent.
 */
class SessionEnvTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun layout(): Triple<File, File, File> {
        val kytyRoot = tmp.newFolder("kyty")
        val rootfsDir = File(kytyRoot, "rootfs").apply { mkdirs() }
        val emulatorBinary = File(kytyRoot, "bin/kyty_emulator").apply {
            parentFile.mkdirs()
            createNewFile()
        }
        return Triple(kytyRoot, rootfsDir, emulatorBinary)
    }

    private fun envMap(env: List<Pair<String, String>>): Map<String, String> {
        // No key may appear twice: native setenv is last-wins, so a
        // duplicate would silently change precedence.
        assertEquals(env.size, env.map { it.first }.toSet().size)
        return env.toMap()
    }

    @Test
    fun emulatedLibsAreColonSeparated() {
        val (kytyRoot, rootfsDir, emulatorBinary) = layout()
        val env = envMap(
            SessionEnv.build(
                kytyRoot, rootfsDir, emulatorBinary,
                File(kytyRoot, "bridge.shm"), EmuSettings(),
            ),
        )
        assertEquals("libvulkan.so.1:libvulkan.so", env["BOX64_EMULATED_LIBS"])
        assertEquals(2, env["BOX64_EMULATED_LIBS"]!!.split(":").size)
    }

    @Test
    fun libraryPathPointsIntoRootfsFirst() {
        val (kytyRoot, rootfsDir, emulatorBinary) = layout()
        val env = envMap(
            SessionEnv.build(
                kytyRoot, rootfsDir, emulatorBinary,
                File(kytyRoot, "bridge.shm"), EmuSettings(),
            ),
        )
        val ldPath = env["BOX64_LD_LIBRARY_PATH"]!!
        assertTrue(
            ldPath.startsWith(
                File(rootfsDir, "usr/lib/x86_64-linux-gnu").absolutePath + ":",
            ),
        )
    }

    @Test
    fun basePathIsTheBinaryDirectory() {
        val (kytyRoot, rootfsDir, emulatorBinary) = layout()
        val env = envMap(
            SessionEnv.build(
                kytyRoot, rootfsDir, emulatorBinary,
                File(kytyRoot, "bridge.shm"), EmuSettings(),
            ),
        )
        assertEquals(
            File(kytyRoot, "bin").absolutePath,
            env["KYTY_BASE_PATH"],
        )
    }

    @Test
    fun logLevelDefaultsToInfoAndHonorsSettings() {
        val (kytyRoot, rootfsDir, emulatorBinary) = layout()
        val shm = File(kytyRoot, "bridge.shm")
        val defaultEnv = envMap(
            SessionEnv.build(kytyRoot, rootfsDir, emulatorBinary, shm, EmuSettings()),
        )
        assertEquals("1", defaultEnv["BOX64_LOG"])
        val verboseEnv = envMap(
            SessionEnv.build(
                kytyRoot, rootfsDir, emulatorBinary, shm,
                EmuSettings().copy(box64Log = 3),
            ),
        )
        assertEquals("3", verboseEnv["BOX64_LOG"])
    }

    @Test
    fun translatorFlagsFollowSettings() {
        val (kytyRoot, rootfsDir, emulatorBinary) = layout()
        val shm = File(kytyRoot, "bridge.shm")
        val on = envMap(
            SessionEnv.build(kytyRoot, rootfsDir, emulatorBinary, shm, EmuSettings()),
        )
        assertEquals("1", on["BOX64_DYNAREC"])
        val off = envMap(
            SessionEnv.build(
                kytyRoot, rootfsDir, emulatorBinary, shm,
                EmuSettings().copy(box64Dynarec = false),
            ),
        )
        assertEquals("0", off["BOX64_DYNAREC"])
    }
}
