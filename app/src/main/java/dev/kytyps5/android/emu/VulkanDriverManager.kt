package dev.kytyps5.android.emu

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

/**
 * Custom Vulkan driver manager (adrenotools pipeline).
 *
 * A driver arrives as a user-picked ZIP (typically a Turnip/Mesa build, e.g.
 * "turnip-25.1.7-android-ndk.zip"). Import extracts the arm64 driver
 * libraries into filesDir/drivers/<id>/ with the main driver library
 * flattened to the directory root — exactly the layout adrenotools expects:
 * customDriverDir is used as the search path of the driver linker namespace
 * and customDriverName (the bare soname) is dlopen()ed from it.
 *
 * At launch, EmulatorSession hands the driver dir + soname to
 * NativeBridge.installVulkanDriver(), which loads it via
 * adrenotools_open_libvulkan() and injects the loader handle into box64.
 */
object VulkanDriverManager {

    const val SYSTEM_DRIVER_ID = "system"

    private const val DIR_NAME = "drivers"
    private const val META_FILE = "driver.json"

    data class VulkanDriver(
        val id: String,
        val name: String,
        val soname: String,
        val version: String,
        val sizeBytes: Long,
        val importedAtMs: Long,
    )

    sealed class ImportResult {
        data class Ok(val driver: VulkanDriver) : ImportResult()
        data class Invalid(val reason: String) : ImportResult()
        data class Failed(val reason: String) : ImportResult()
    }

    fun driversRoot(context: Context): File = File(context.filesDir, DIR_NAME)

    fun driverDir(context: Context, id: String): File = File(driversRoot(context), id)

    fun list(context: Context): List<VulkanDriver> {
        val root = driversRoot(context)
        if (!root.isDirectory) {
            return emptyList()
        }
        return root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val meta = File(dir, META_FILE)
                if (!meta.exists()) return@mapNotNull null
                try {
                    val o = JSONObject(meta.readText())
                    VulkanDriver(
                        id = dir.name,
                        name = o.getString("name"),
                        soname = o.getString("soname"),
                        version = o.optString("version", ""),
                        sizeBytes = o.optLong("sizeBytes", 0L),
                        importedAtMs = o.optLong("importedAtMs", 0L),
                    )
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun get(context: Context, id: String): VulkanDriver? =
        list(context).firstOrNull { it.id == id }

    fun delete(context: Context, id: String): Boolean {
        if (!id.matches(Regex("[a-z0-9._-]+"))) {
            return false
        }
        val dir = driverDir(context, id)
        if (!dir.isDirectory) {
            return false
        }
        return dir.deleteRecursively()
    }

    /**
     * Imports a driver ZIP picked through SAF. Fully offline: streams the
     * zip once into a staging dir, validates that the archive really carries
     * an arm64 ELF driver library, then relocates the libraries into the
     * adrenotools layout.
     */
    fun importFromZip(context: Context, uri: Uri): ImportResult {
        val displayName = queryDisplayName(context, uri) ?: "driver"
        val staging = File(context.cacheDir, "driver-import-${System.currentTimeMillis()}")

        try {
            staging.mkdirs()
            val extracted = mutableListOf<String>() // relative paths of files written

            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw, 128 * 1024)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        // zip-slip + junk filtering
                        val clean = name.removePrefix("./")
                        if (clean.isNotEmpty() && !clean.contains("../") &&
                            !clean.startsWith("/") && !clean.contains("__MACOSX")
                        ) {
                            if (entry.isDirectory) {
                                File(staging, clean).mkdirs()
                            } else {
                                val target = File(staging, clean)
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out ->
                                    zis.copyTo(out, 128 * 1024)
                                }
                                extracted.add(clean)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: return ImportResult.Failed("stream indisponível")

            if (extracted.isEmpty()) {
                return ImportResult.Invalid("ZIP vazio ou ilegível")
            }

            // candidate main driver: ELF .so whose basename looks like a
            // Vulkan ICD. Priority: hw-style ICD name > turnip names.
            data class Candidate(val relPath: String, val base: String, val prio: Int)

            val candidates = extracted
                .filter { it.endsWith(".so") }
                .mapNotNull { rel ->
                    val base = rel.substringAfterLast('/')
                    val prio = when {
                        base.matches(Regex("vulkan\\..+\\.so")) -> 0
                        base == "libvulkan_freedesktop.so" -> 1
                        base.startsWith("libvulkan_") -> 2
                        // "libvulkan.so" is deliberately NOT accepted: that is
                        // the Android Vulkan *loader*, not a driver — loading
                        // it as the ICD yields zero physical devices.
                        else -> return@mapNotNull null
                    }
                    Candidate(rel, base, prio)
                }
                .sortedWith(compareBy({ it.prio }, { it.relPath }))

            if (candidates.isEmpty()) {
                return ImportResult.Invalid(
                    "nenhuma biblioteca de driver Vulkan encontrada (procure um ZIP do Turnip)")
            }

            // pick the first candidate that is genuinely an arm64 shared object
            var main: Candidate? = null
            for (c in candidates) {
                if (isArm64ElfSharedObject(File(staging, c.relPath))) {
                    main = c
                    break
                }
            }
            if (main == null) {
                return ImportResult.Invalid(
                    "o driver no ZIP não é uma biblioteca arm64 válida")
            }
            val mainFile = File(staging, main.relPath)

            // stable driver id from the zip display name
            val idBase = displayName.substringBeforeLast('.')
                .lowercase()
                .replace(Regex("[^a-z0-9._-]+"), "-")
                .trim('-', '.')
                .take(40)
                .ifEmpty { "driver" }
            val root = driversRoot(context)
            root.mkdirs()
            var id = idBase
            var n = 2
            while (File(root, id).exists()) {
                id = "$idBase-${n++}"
            }
            val driverDir = File(root, id)
            driverDir.mkdirs()

            val totalBytes = relocate(staging, extracted, main.relPath, driverDir)

            val version = detectVersion(displayName, File(driverDir, main.base))
            val driver = VulkanDriver(
                id = id,
                name = displayName.substringBeforeLast('.').ifEmpty { id },
                soname = main.base,
                version = version,
                sizeBytes = totalBytes,
                importedAtMs = System.currentTimeMillis(),
            )

            File(driverDir, META_FILE).writeText(
                JSONObject()
                    .put("name", driver.name)
                    .put("soname", driver.soname)
                    .put("version", driver.version)
                    .put("sizeBytes", driver.sizeBytes)
                    .put("importedAtMs", driver.importedAtMs)
                    .toString()
            )

            return ImportResult.Ok(driver)
        } catch (e: Exception) {
            return ImportResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Moves every extracted file from staging into the final driver dir.
     * Common leading components ("lib/", "lib64/", "arm64-v8a/") are stripped
     * so companion libraries land next to the main driver; the main driver
     * itself is always flattened to the root because adrenotools dlopens it
     * by bare soname from the namespace search path.
     */
    private fun relocate(staging: File, extracted: List<String>, mainRel: String,
                         driverDir: File): Long {
        var total = 0L
        for (rel in extracted) {
            val src = File(staging, rel)
            if (!src.isFile) continue
            val finalRel: String
            var parts = rel.split('/')
            // strip known container dirs from the front
            while (parts.size > 1 && parts[0] in setOf("lib", "lib64", "arm64-v8a")) {
                parts = parts.drop(1)
            }
            finalRel = if (rel == mainRel) {
                src.name // main driver: root of the driver dir
            } else {
                parts.joinToString("/")
            }
            val dst = File(driverDir, finalRel)
            dst.parentFile?.mkdirs()
            if (src.renameTo(dst) || runCatching { src.copyTo(dst, overwrite = true) }.isSuccess) {
                total += dst.length()
            }
        }
        return total
    }

    /** ELF magic + 64-bit + EM_AARCH64 + ET_DYN. */
    private fun isArm64ElfSharedObject(f: File): Boolean {
        try {
            val head = ByteArray(20)
            FileInputStream(f).use { it.read(head) }
            if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
                head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
            ) return false
            if (head[4] != 2.toByte()) return false // ELFCLASS64
            val machine = (head[19].toInt() and 0xFF shl 8) or (head[18].toInt() and 0xFF)
            if (machine != 183) return false // EM_AARCH64
            val type = (head[17].toInt() and 0xFF shl 8) or (head[16].toInt() and 0xFF)
            return type == 3 // ET_DYN (shared object / PIE)
        } catch (e: Exception) {
            return false
        }
    }

    /** "25.1.7" out of "turnip-25.1.7-android-ndk.zip", or from the .so's
     *  embedded Mesa version string. Empty when nothing is found. */
    private fun detectVersion(displayName: String, mainSo: File): String {
        Regex("(\\d+\\.\\d+(?:\\.\\d+)?)").find(displayName)?.let { return it.value }
        try {
            RandomAccessFile(mainSo, "r").use { raf ->
                val len = minOf(raf.length(), 3L * 1024 * 1024).toInt()
                if (len <= 0) return ""
                val buf = ByteArray(len)
                raf.readFully(buf)
                val text = String(buf, Charsets.ISO_8859_1)
                Regex("Mesa[ ]([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)")
                    .find(text)?.let { return it.groupValues[1] }
                Regex("(?:turnip|mesa)[^\\x20-\\x7e]{0,4}([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)",
                    RegexOption.IGNORE_CASE)
                    .find(text)?.let { return it.groupValues[1] }
            }
        } catch (e: Exception) {
            // fall through
        }
        return ""
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
