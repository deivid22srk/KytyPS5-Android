package dev.kytyps5.android.emu

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Installs the x86_64 emulator runtime from APK assets into app storage.
 *
 * The APK bundles:
 *   assets/kyty/bin/kyty_emulator          — x86_64-linux-android (bionic) ELF
 *                                           (runs under box64; its DT_NEEDED are
 *                                           the device's own system libraries)
 *   assets/kyty/rootfs.tar                 — runtime skeleton, plain GNU tar
 *                                           (uncompressed — aapt2 strips/gunzips
 *                                           ".gz" assets, so the name must match)
 *   assets/kyty/runtime_version.txt        — version marker
 * box64 ships as jniLibs/arm64-v8a/libbox64.so and is reachable through the
 * app class-loader namespace (useLegacyPackaging=false keeps it inside
 * base.apk) or, on devices that extract it, nativeLibraryDir.
 */
object RuntimeInstaller {

    private const val ASSET_PREFIX = "kyty"
    private const val VERSION_ASSET = "$ASSET_PREFIX/runtime_version.txt"
    private const val TAG = "KytyInstall"

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
        val bundled = bundledVersion(context)
        if (bundled == null) {
            Log.e(TAG, "APK does not bundle $VERSION_ASSET — runtime cannot be installed")
            return false
        }
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
        Log.i(TAG, "install start (bundled=${bundledVersion(context)} installed=${installedVersion(context)})")

        // 1. emulator binary (raw asset)
        val emuAsset = "$ASSET_PREFIX/bin/kyty_emulator"
        try {
            context.assets.open(emuAsset).use { input ->
                File(binDir, "kyty_emulator").outputStream().use { output ->
                    input.copyTo(output, 1 shl 16)
                }
            }
            File(binDir, "kyty_emulator").setExecutable(true, false)
            Log.i(TAG, "emulator binary installed: ${File(binDir, "kyty_emulator").length() / (1024 * 1024)} MB")
            onProgress(10)
        } catch (e: Exception) {
            Log.e(TAG, "emulator asset install failed", e)
            return false
        }

        // 2. rootfs archive (pure-Kotlin tar reader; aapt2 gunzips ".gz" assets
        // and drops the suffix, so the APK always carries a plain tar here).
        // APK assets are deflate-compressed, so openFd() cannot give us the
        // length; stage the tar into the cache dir for a known-size stream.
        val rootfsAsset = "$ASSET_PREFIX/rootfs.tar"
        val rootfsDir = File(kytyRoot, "rootfs")
        rootfsDir.mkdirs()
        rootfsDir.deleteRecursively()
        rootfsDir.mkdirs()
        val stagedTar = File(context.cacheDir, "rootfs-install.tar")
        try {
            stagedTar.outputStream().use { out ->
                context.assets.open(rootfsAsset).use { input ->
                    input.copyTo(out, 1 shl 16)
                }
            }
            Log.i(TAG, "rootfs tar staged: ${stagedTar.length()} bytes")
            TarExtractor.extract(
                stagedTar.inputStream().buffered(),
                rootfsDir,
                stagedTar.length(),
            ) { pct ->
                onProgress(10 + (pct * 90) / 100)
            }
            Log.i(TAG, "rootfs extracted: ${rootfsDir.walkTopDown().filter { it.isFile }.count()} files")
        } catch (e: Exception) {
            Log.e(TAG, "rootfs asset install failed ($rootfsAsset)", e)
            return false
        } finally {
            stagedTar.delete()
        }

        // 3. version marker
        bundledVersion(context)?.let {
            File(kytyRoot, "runtime_version.txt").writeText(it)
        }
        onProgress(100)
        Log.i(TAG, "install complete")
        return true
    }
}

/**
 * Minimal but correct GNU/ustar tar reader: regular files, directories,
 * symlinks (target taken from the header linkname field), hardlinks, GNU
 * long-name/long-link extensions ('L'/'K'), POSIX ustar prefix names,
 * base-256 (GNU binary) size fields and header checksum validation so a
 * corrupt or misaligned archive fails loudly instead of extracting garbage.
 *
 * Pure JVM logic (android APIs only inside guarded symlink fallbacks) so
 * it is unit-testable on the host against real GNU tar archives.
 */
object TarExtractor {

    fun extract(
        input: InputStream,
        targetDir: File,
        totalBytes: Long = 0L,
        onProgress: (Int) -> Unit = {},
    ) {
        val header = ByteArray(512)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        var paxLocal: Map<String, String> = emptyMap()
        var totalRead = 0L
        val seenFiles = HashMap<String, File>() // hardlink resolution
        val deferredLinks = mutableListOf<Pair<String, File>>() // symlink retry

        while (true) {
            if (!readFully(input, header)) {
                break // EOF
            }
            if (header.all { it == 0.toByte() }) {
                continue // zero block (padding / end)
            }
            if (!checksumOk(header)) {
                throw IOException("tar header checksum mismatch — corrupt archive or misaligned stream")
            }

            val typeFlag = header[156]

            // pax extended header: records override the next entry's fields
            if (typeFlag == 'x'.code.toByte() || typeFlag == 'g'.code.toByte()) {
                val records = readPaxRecords(input, size(header))
                if (typeFlag == 'x'.code.toByte()) {
                    paxLocal = records
                }
                continue
            }

            val name = paxLocal["path"] ?: pendingLongName ?: readString(header, 0, 100)
            val linkName = paxLocal["linkpath"] ?: pendingLongLink ?: readString(header, 157, 100)
            pendingLongName = null
            pendingLongLink = null
            val size = paxLocal["size"]?.toLongOrNull() ?: readNumeric(header, 124, 12)
            val mode = paxLocal["mode"]?.toLongOrNull(8) ?: readNumeric(header, 100, 8)
            paxLocal = emptyMap()
            // prefix is a POSIX-ustar feature; GNU headers ("ustar ") reuse
            // that zone for atime/ctime, so only trust it for real ustar
            val isPosixUstar = readString(header, 257, 5) == "ustar" && header[262] == 0.toByte()
            val prefix = if (isPosixUstar) readString(header, 345, 155) else ""

            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            if (fullName.isEmpty() || fullName.startsWith("/")) {
                skipFully(input, size)
                continue
            }
            val outPath = sanitize(targetDir, fullName)

            // stream consumption for progress: header + padded payload
            totalRead += 512L + ((size + 511L) / 512L) * 512L

            when (typeFlag) {
                'L'.code.toByte() -> { // GNU long name follows
                    pendingLongName = readGnuLongField(input, size)
                    continue
                }
                'K'.code.toByte() -> { // GNU long link target follows
                    pendingLongLink = readGnuLongField(input, size)
                    continue
                }
                '5'.code.toByte() -> { // directory
                    outPath.mkdirs()
                    skipFully(input, size)
                }
                '2'.code.toByte() -> { // symlink: target lives in the linkname
                    // header field; size is 0 for well-formed archives
                    skipFully(input, size)
                    if (!tryCreateLink(targetDir, outPath, linkName)) {
                        // target may appear later in the archive — retry at the end
                        deferredLinks.add(linkName to outPath)
                    }
                }
                '1'.code.toByte() -> { // hardlink: duplicate a seen file
                    skipFully(input, size)
                    val src = seenFiles[normalizeKey(linkName)]
                    if (src != null && src.isFile) {
                        outPath.parentFile?.mkdirs()
                        try {
                            src.copyTo(outPath, overwrite = true)
                        } catch (e: Exception) {
                            // a missing hardlink is non-fatal
                        }
                    }
                }
                '0'.code.toByte(), 0.toByte(), 7.toByte() -> { // regular file
                    outPath.parentFile?.mkdirs()
                    outPath.outputStream().use { out ->
                        var remaining = size
                        val buf = ByteArray(1 shl 16)
                        while (remaining > 0) {
                            val want = minOf(remaining, buf.size.toLong()).toInt()
                            val n = input.read(buf, 0, want)
                            if (n <= 0) {
                                throw IOException("tar truncated while reading ${outPath.name}")
                            }
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    if ((mode and 0x49L) != 0L) { // any exec bit (0o111)
                        outPath.setExecutable(true, false)
                    }
                    skipPadding(input, size)
                    seenFiles[normalizeKey(fullName)] = outPath
                    if (totalBytes > 0) {
                        onProgress(((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100))
                    }
                }
                else -> {
                    // fifos, devices…: skip payload
                    skipFully(input, size)
                }
            }
        }
        // second pass: symlinks whose target appeared later in the archive
        // (matters on filesystems without symlink support, where the
        // materialized-copy fallback needs the target to already exist)
        for ((target, out) in deferredLinks) {
            tryCreateLink(targetDir, out, target)
        }
        onProgress(100)
    }

    /** Creates a symlink with the correct target resolution and graceful
     *  fallbacks (Files API → android.system.Os → materialized copy), so the
     *  loader chain (ld-linux → libc → …) resolves even without symlink
     *  support on the filesystem.
     *
     *  Absolute targets are stored as absolute paths pointing INSIDE the
     *  extracted rootfs (chroot-style mapping): a symlink target is resolved
     *  relative to the LINK's directory, not to the archive root.
     *
     *  Returns true when the path now exists at [outPath] (as a link or a
     *  materialized copy); false when the target has not been extracted yet. */
    private fun tryCreateLink(targetDir: File, outPath: File, linkTarget: String): Boolean {
        if (linkTarget.isEmpty()) {
            return false
        }
        val targetPath = if (linkTarget.startsWith("/")) {
            // absolute path: map it into the rootfs, stored absolutely
            sanitize(targetDir, linkTarget).absoluteFile.toPath()
        } else {
            // relative path: resolve against the link's own directory
            (outPath.parentFile ?: targetDir).toPath().resolve(linkTarget)
        }
        outPath.parentFile?.mkdirs()
        try {
            outPath.delete()
            java.nio.file.Files.createSymbolicLink(outPath.toPath(), targetPath)
            return true
        } catch (e: Exception) {
            // fall through
        }
        try {
            android.system.Os.symlink(targetPath.toString(), outPath.absolutePath)
            return true
        } catch (e: Exception) {
            // fall through
        }
        // last resort: materialize a copy of the target file
        return try {
            val src = targetPath.toFile()
            if (src.isFile) {
                src.copyTo(outPath, overwrite = true)
                true
            } else {
                false // target not extracted yet — caller may retry
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Reads a GNU 'L'/'K' extension payload (a NUL-terminated string). */
    private fun readGnuLongField(input: InputStream, size: Long): String? {
        if (size <= 0L || size > (1L shl 20)) {
            skipFully(input, size)
            return null
        }
        val data = ByteArray(size.toInt())
        if (!readFully(input, data)) {
            return null
        }
        val end = data.indexOfFirst { it == 0.toByte() }.let { if (it < 0) data.size else it }
        skipPadding(input, size)
        return data.copyOfRange(0, end).decodeToString()
    }

    /** Parses a pax extended header payload: records of the form
     *  `<len> <keyword>=<value>\n` where len counts the whole record. */
    private fun readPaxRecords(input: InputStream, size: Long): Map<String, String> {
        val result = HashMap<String, String>()
        if (size <= 0L || size > (1L shl 24)) {
            skipFully(input, size)
            return result
        }
        val data = ByteArray(size.toInt())
        if (!readFully(input, data)) {
            return result
        }
        skipPadding(input, size)
        var pos = 0
        while (pos < data.size) {
            var sp = pos
            while (sp < data.size && data[sp] != ' '.code.toByte()) {
                sp++
            }
            if (sp >= data.size) {
                break
            }
            val len = data.copyOfRange(pos, sp).decodeToString().trim().toIntOrNull() ?: break
            if (len <= 0 || pos + len > data.size) {
                break
            }
            val rec = data.copyOfRange(pos, pos + len).decodeToString()
            val eq = rec.indexOf('=')
            if (eq > 0) {
                val key = rec.substring(rec.indexOf(' ') + 1, eq)
                result[key] = rec.substring(eq + 1).trimEnd('\n')
            }
            pos += len
        }
        return result
    }

    private fun size(header: ByteArray): Long = readNumeric(header, 124, 12)

    private fun normalizeKey(name: String): String =
        name.trimEnd('/').removePrefix("./").removePrefix("/")

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

    /** Octal field, with GNU base-256 (binary) support for large values. */
    private fun readNumeric(buf: ByteArray, off: Int, len: Int): Long {
        if (len > 0 && (buf[off].toInt() and 0x80) != 0) {
            var v = buf[off].toLong() and 0x7f
            for (i in 1 until len) {
                v = (v shl 8) or (buf[off + i].toLong() and 0xff)
            }
            return v
        }
        val s = readString(buf, off, len).trim()
        return if (s.isEmpty()) 0L else s.toLongOrNull(8) ?: 0L
    }

    /** ustar checksum: signed sum with the chksum field read as spaces. */
    private fun checksumOk(header: ByteArray): Boolean {
        val storedStr = readString(header, 148, 8).trim()
        val stored = storedStr.toLongOrNull(8) ?: return false
        var sum = 0L
        for (i in header.indices) {
            sum += (if (i in 148..155) 0x20 else (header[i].toInt() and 0xff)).toLong()
        }
        return sum == stored
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
        // n bytes of payload were consumed (or are about to be); advance to
        // the next 512-byte boundary
        val pad = ((512 - (n % 512)) % 512)
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
