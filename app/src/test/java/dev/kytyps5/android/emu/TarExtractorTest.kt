package dev.kytyps5.android.emu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/**
 * Host-JVM tests for the pure-Kotlin tar reader used by the first-run
 * runtime installer. Builds real archives with the system GNU tar in three
 * formats (gnu, ustar, pax) and verifies byte-exact extraction, exec bits,
 * symlinks, hardlinks and GNU long names.
 *
 * Regression background: a previous skipPadding off-by-one
 * ((511 - n%512) % 512) desynchronized the stream after every entry with
 * size % 512 != 0, which surfaced on device as IllegalArgumentException
 * (copyOfRange 0 > -1) inside the GNU long-name branch while reading
 * garbage bytes as a header.
 */
class TarExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun run(vararg cmd: String) {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString()
        val rc = proc.waitFor()
        assertEquals("command failed (${cmd.joinToString(" ")}): $out", 0, rc)
    }

    /** Fixture tree exercising every header shape the reader must handle. */
    private fun makeTree(): File {
        val src = tmp.newFolder("fixture-src")
        val rnd = Random(42)
        File(src, "usr/lib/x86_64-linux-gnu").mkdirs()
        File(src, "etc/ld.so.conf.d").mkdirs()
        File(src, "bin").mkdirs()
        // sizes around the 512 boundary — the old off-by-one broke on every
        // size % 512 != 0, and size == 0 skipped 511 phantom bytes
        val sizes = intArrayOf(0, 1, 100, 511, 512, 513, 1024, 4096, 1 shl 20)
        for ((i, sz) in sizes.withIndex()) {
            val bytes = ByteArray(sz)
            rnd.nextBytes(bytes)
            File(src, "usr/lib/x86_64-linux-gnu/libsz$i.so").writeBytes(bytes)
        }
        // exec-bit file
        val exec = File(src, "bin/kyty_emulator")
        exec.writeBytes(ByteArray(2048) { (it * 7).toByte() })
        exec.setExecutable(true, false)
        File(src, "etc/ld.so.conf.d/x86_64-linux-gnu.conf").writeText("usr/lib/x86_64-linux-gnu\n")
        // relative symlink (target lives in the linkname header field)
        run("ln", "-s", "libsz3.so", File(src, "usr/lib/x86_64-linux-gnu/libfoo.so.1").absolutePath)
        // absolute symlink: extraction must resolve it inside the rootfs
        // (target: libsz4.so — the 512-byte fixture file)
        val absLink = File(src, "usr/lib64/ld-linux-x86-64.so.2")
        absLink.parentFile.mkdirs()
        run(
            "ln", "-s", "/usr/lib/x86_64-linux-gnu/libsz4.so",
            absLink.absolutePath,
        )
        // hardlink: a second name for the same content
        run(
            "ln",
            File(src, "usr/lib/x86_64-linux-gnu/libsz0.so").absolutePath,
            File(src, "usr/lib/x86_64-linux-gnu/libsz0-hard.so").absolutePath,
        )
        // GNU long name (> 100 chars) — forces the 'L' extension in gnu format
        val deep = StringBuilder("usr/lib/x86_64-linux-gnu/")
        while (deep.length < 130) {
            deep.append("verydeepcomponent/")
        }
        deep.append("libdeep.so")
        val deepFile = File(src, deep.toString())
        deepFile.parentFile.mkdirs()
        deepFile.writeBytes(ByteArray(777) { (it * 3).toByte() })
        return src
    }

    private fun assertTreeExtracted(src: File, out: File) {
        // every regular file, byte-exact
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(src).path
            val got = File(out, rel)
            assertTrue("missing extracted file: $rel", got.isFile)
            assertEquals("size mismatch: $rel", f.length(), got.length())
            assertTrue("content mismatch: $rel", f.readBytes().contentEquals(got.readBytes()))
        }
        // exec bit preserved
        assertTrue(File(out, "bin/kyty_emulator").canExecute())
        assertFalse(File(out, "etc/ld.so.conf.d/x86_64-linux-gnu.conf").canExecute())
        // symlinks
        val rel = File(out, "usr/lib/x86_64-linux-gnu/libfoo.so.1")
        assertTrue("symlink not created (or target missing)", rel.exists())
        if (java.nio.file.Files.isSymbolicLink(rel.toPath())) {
            assertEquals(
                "libsz3.so",
                java.nio.file.Files.readSymbolicLink(rel.toPath()).toFile().name,
            )
        }
        val abs = File(out, "usr/lib64/ld-linux-x86-64.so.2")
        assertTrue("absolute symlink not resolved inside rootfs", abs.exists())
        // hardlink content
        assertEquals(
            File(src, "usr/lib/x86_64-linux-gnu/libsz0.so").readBytes().contentToString(),
            File(out, "usr/lib/x86_64-linux-gnu/libsz0-hard.so").readBytes().contentToString(),
        )
    }

    private fun testFormat(format: String) {
        val src = makeTree()
        val tar = File(tmp.root, "fixture-$format.tar")
        run("tar", "--format=$format", "-cf", tar.absolutePath, "-C", src.absolutePath, "./")
        val out = tmp.newFolder("out-$format")
        TarExtractor.extract(tar.inputStream().buffered(), out, tar.length()) { }
        assertTreeExtracted(src, out)
    }

    @Test
    fun extractsGnuFormat() {
        testFormat("gnu")
    }

    @Test
    fun extractsUstarFormat() {
        testFormat("ustar")
    }

    @Test
    fun extractsPaxFormat() {
        testFormat("posix")
    }

    @Test
    fun reportsProgressAgainstKnownTotal() {
        val src = makeTree()
        val tar = File(tmp.root, "progress.tar")
        run("tar", "-cf", tar.absolutePath, "-C", src.absolutePath, "./")
        val seen = mutableListOf<Int>()
        TarExtractor.extract(tar.inputStream().buffered(), tmp.newFolder(), tar.length()) {
            seen.add(it)
        }
        assertTrue("no progress reported", seen.isNotEmpty())
        assertEquals(100, seen.last())
        assertTrue("progress must be monotonic", seen.zipWithNext().all { (a, b) -> a <= b })
    }

    /** The exact artifact shipped inside the APK, when present (CI builds it
     *  before running unit tests; local dev after scripts/android-make-rootfs.sh). */
    @Test
    fun extractsRealBundledRootfs() {
        val assetTar = File("src/main/assets/kyty/rootfs.tar")
        assumeTrue("rootfs.tar not built — skipping", assetTar.isFile)
        val out = tmp.newFolder("rootfs-out")
        TarExtractor.extract(assetTar.inputStream().buffered(), out, assetTar.length()) { }
        // The bionic-guest runtime skeleton: the session's readiness check
        // (EmulatorSession.runtimeReady) looks for the release markers. The
        // guest's libraries are the device's own bionic — wrapped by box64
        // in-process — so no glibc runtime ships inside the APK anymore.
        assertTrue("release marker missing", File(out, "etc/kyty-release").isFile)
        assertTrue("release text missing", File(out, "etc/kyty-release.txt").isFile)
        assertEquals("kyty-android-bionic", File(out, "etc/kyty-release").readText().trim())
    }

    @Test
    fun rejectsCorruptHeaderWithClearError() {
        val src = makeTree()
        val tar = File(tmp.root, "corrupt.tar")
        run("tar", "-cf", tar.absolutePath, "-C", src.absolutePath, "./")
        val bytes = tar.readBytes()
        // flip a byte inside the first header's name field — the stored
        // checksum no longer matches
        bytes[10] = (bytes[10].toInt() xor 0x55).toByte()
        val corrupt = File(tmp.root, "corrupt-flipped.tar")
        corrupt.writeBytes(bytes)
        var threw = false
        try {
            TarExtractor.extract(corrupt.inputStream().buffered(), tmp.newFolder()) { }
        } catch (e: java.io.IOException) {
            threw = true
            assertTrue(e.message!!.contains("checksum"))
        }
        assertTrue("corrupt archive must fail loudly", threw)
    }
}
