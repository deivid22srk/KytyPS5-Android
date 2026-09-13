package dev.kytyps5.android.emu

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Installs the x86_64 emulator runtime from APK assets into app storage.
 *
 * The APK bundles:
 *   assets/kyty/bin/kyty_emulator          — x86_64 Linux ELF (runs under box64)
 *   assets/kyty/rootfs.tar.gz              — minimal Debian x86_64 sysroot (GNU tar)
 *   assets/kyty/runtime_version.txt        — version marker
 * box64 ships as jniLibs/arm64-v8a/libbox64.so and is installed by the
 * package manager directly into nativeLibraryDir.
 */
object RuntimeInstaller {

    private const val ASSET_PREFIX = "kyty"
    private const val VERSION_ASSET = "$ASSET_PREFIX/runtime_version.txt"

    fun installedVersion(context: Context): String? {
        val marker = File(File(context.filesDir, "kyty"), "runtime_version.txt")
        return if (marker.exists()) marker.readText().trim() else null
    }

    fun bundledVersion(context: Context): String? {
        return try {
            context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            null
        }
    }

    fun needsInstall(context: Context): Boolean {
        val bundled = bundledVersion(context) ?: return false
        return installedVersion(context) != bundled
    }

    /**
     * Extracts the runtime. [onProgress] receives 0..100. Returns true on
     * success (false when the APK does not bundle a runtime).
     */
    fun install(context: Context, onProgress: (Int) -> Unit = {}): Boolean {
        val kytyRoot = File(context.filesDir, "kyty")
        val binDir = File(kytyRoot, "bin")
        binDir.mkdirs()

        // 1. emulator binary (raw asset)
        val emuAsset = "$ASSET_PREFIX/bin/kyty_emulator"
        try {
            context.assets.open(emuAsset).use { input ->
                File(binDir, "kyty_emulator").outputStream().use { output ->
                    input.copyTo(output, 1 shl 16)
                }
            }
            File(binDir, "kyty_emulator").setExecutable(true, false)
            onProgress(10)
        } catch (e: Exception) {
            return false
        }

        // 2. rootfs archive (pure-Kotlin tar.gz reader)
        val rootfsAsset = "$ASSET_PREFIX/rootfs.tar.gz"
        val rootfsDir = File(kytyRoot, "rootfs")
        rootfsDir.mkdirs()
        rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()
        try {
            context.assets.open(rootfsAsset).use { raw ->
                GZIPInputStream(raw, 1 shl 16).use { gz ->
                    TarExtractor.extract(gz, rootfsDir) { pct ->
                        onProgress(10 + (pct * 90) / 100)
                    }
                }
            }
        } catch (e: Exception) {
            return false
        }

        // 3. version marker
        bundledVersion(context)?.let {
            File(kytyRoot, "runtime_version.txt").writeText(it)
        }
        onProgress(100)
        return true
    }
}

/** Minimal GNU-tar reader: regular files, directories, symlinks, longnames. */
object TarExtractor {

    fun extract(input: InputStream, targetDir: File, onProgress: (Int) -> Unit) {
        val header = ByteArray(512)
        var pendingLongName: String? = null
        var totalRead = 0L

        while (true) {
            if (!readFully(input, header)) {
                break // EOF
            }
            if (header.all { it == 0.toByte() }) {
                continue // zero block (padding / end)
            }
            val name = pendingLongName ?: readString(header, 0, 100)
            pendingLongName = null
            val size = readOctal(header, 124, 12)
            val typeFlag = header[156]
            val prefix = readString(header, 345, 155)
            val mode = readOctal(header, 100, 8)

            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            if (fullName.isEmpty() || fullName.startsWith("/")) {
                skipFully(input, size)
                continue
            }
            val outPath = sanitize(targetDir, fullName)

            when (typeFlag) {
                'L'.code.toByte() -> { // GNU long name follows
                    val data = ByteArray(size.toInt())
                    readFully(input, data)
                    pendingLongName = data.copyOfRange(0, size.toInt() - 1).decodeToString()
                    skipPadding(input, size)
                    continue
                }
                '5'.code.toByte() -> {
                    outPath.mkdirs()
                    skipFully(input, size)
                }
                '2'.code.toByte() -> { // symlink
                    val data = ByteArray(size.coerceAtMost(1024).toInt())
                    readFully(input, data)
                    skipFully(input, size)
                    val linkTarget = readString(data, 0, data.size)
                    try {
                        outPath.delete()
                        java.nio.file.Files.createSymbolicLink(
                            outPath.toPath(),
                            targetDir.toPath().resolve(linkTarget).normalize()
                        )
                    } catch (e: Exception) {
                        // symlinks are optional niceties
                    }
                }
                '0'.code.toByte(), 0.toByte() -> {
                    outPath.parentFile?.mkdirs()
                    var remaining = size
                    val out = outPath.outputStream()
                    val buf = ByteArray(1 shl 16)
                    while (remaining > 0) {
                        val want = minOf(remaining, buf.size.toLong()).toInt()
                        val n = input.read(buf, 0, want)
                        if (n <= 0) {
                            break
                        }
                        out.write(buf, 0, n)
                        remaining -= n
                        totalRead += n
                    }
                    out.close()
                    if ((mode and 0x49L) != 0L) { // any exec bit (0o111)
                        outPath.setExecutable(true, false)
                    }
                    skipPadding(input, size)
                    onProgress(-1) // progress computed by caller from bytes
                }
                else -> {
                    // pax headers, hardlinks, etc: skip payload
                    skipFully(input, size)
                }
            }
        }
        onProgress(100)
    }

    private fun sanitize(root: File, name: String): File {
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        var f = root
        for (p in parts) {
            f = File(f, p)
        }
        return f
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) {
                return false // EOF (clean at boundary, truncated otherwise)
            }
            off += n
        }
        return true
    }

    private fun readString(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        val limit = minOf(off + len, buf.size)
        while (end < limit && buf[end] != 0.toByte()) {
            end++
        }
        return buf.copyOfRange(off, end).decodeToString()
    }

    private fun readOctal(buf: ByteArray, off: Int, len: Int): Long {
        val s = readString(buf, off, len).trim()
        return if (s.isEmpty()) 0L else s.toLongOrNull(8) ?: 0L
    }

    private fun skipFully(input: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) {
                    break
                }
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
        skipPadding(input, n)
    }

    private fun skipPadding(input: InputStream, n: Long) {
        val pad = ((511 - (n % 512)) % 512)
        if (pad > 0) {
            skipNoPad(input, pad)
        }
    }

    private fun skipNoPad(input: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) {
                    break
                }
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }
}
