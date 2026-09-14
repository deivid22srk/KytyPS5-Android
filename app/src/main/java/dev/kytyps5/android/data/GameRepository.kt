package dev.kytyps5.android.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File

/**
 * A game as the emulator understands it: a directory containing eboot.bin.
 *
 * Games come in two flavors:
 *  - copied (imported into app storage), installDir lives under gamesRoot;
 *  - linked ([linked] = true, added with "run in place"): installDir points
 *    at the ORIGINAL folder chosen by the user — nothing was copied, the
 *    emulator reads the files where they are. Deleting a linked game only
 *    removes it from the library, never touches the original files.
 */
data class GameInfo(
    val installDir: File,
    val title: String,
    val titleId: String,
    val appVersion: String,
    val contentId: String,
    val category: String,
    val sizeBytes: Long,
    val linked: Boolean = false,
    /** SAF tree uri of the original folder (linked games only). */
    val treeUri: String? = null,
)

/** Result of a run-in-place (no copy) import attempt. */
enum class LinkResult {
    /** Folder resolved to a readable real path with eboot.bin inside. */
    LINKED,
    /** Folder is on shared storage but the app lacks "All files access"
     *  (or legacy read permission) — the emulator could not read it. */
    NEEDS_PERMISSION,
    /** No eboot.bin found / not a game. */
    NOT_A_GAME,
    /** SAF tree not backed by the filesystem (e.g. cloud provider). */
    UNSUPPORTED_LOCATION,
}

/**
 * Library management. Games are either imported (copied) into app storage
 * via SAF or linked in place (run where they are); metadata comes from the
 * game's own sce_sys/param.sfo — never from placeholder data.
 */
class GameRepository(private val context: Context) {

    val gamesRoot: File
        get() = File(context.filesDir, "kyty/games")

    private val linkedRegistry: File
        get() = File(context.filesDir, "kyty/linked_games.json")

    fun scan(): List<GameInfo> {
        val copied = scanCopied()
        val linked = scanLinked()
        return (copied + linked).sortedBy { it.title.lowercase() }
    }

    private fun scanCopied(): List<GameInfo> {
        val root = gamesRoot
        if (!root.exists()) {
            return emptyList()
        }
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val eboot = findEboot(dir) ?: return@mapNotNull null
                /* the emulator needs eboot.bin at the game dir root; games
                 * imported with an extra nesting level launch from the dir
                 * that actually contains it */
                val gameDir = eboot.parentFile ?: dir
                val sfo = File(gameDir, "sce_sys/param.sfo")
                val map = if (sfo.exists()) ParamSfo.parse(sfo) else emptyMap()
                GameInfo(
                    installDir = gameDir,
                    title = ParamSfo.title(map).ifEmpty { gameDir.name },
                    titleId = ParamSfo.titleId(map).ifEmpty { gameDir.name },
                    appVersion = ParamSfo.appVersion(map),
                    contentId = ParamSfo.contentId(map),
                    category = ParamSfo.category(map),
                    sizeBytes = gameDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                )
            }
            ?: emptyList()
    }

    /** Linked (run-in-place) games from the persistent registry, validated. */
    private fun scanLinked(): List<GameInfo> {
        val registry = readRegistry() ?: return emptyList()
        return registry.mapNotNull { entry ->
            val dir = File(entry.path)
            if (!dir.isDirectory) {
                return@mapNotNull null /* original moved/deleted */
            }
            GameInfo(
                installDir = dir,
                title = entry.title.ifEmpty { dir.name },
                titleId = entry.titleId.ifEmpty { dir.name },
                appVersion = entry.appVersion,
                contentId = entry.contentId,
                category = entry.category,
                /* size was measured at link time: walking a multi-GB tree on
                 * FUSE storage at every scan would freeze the library */
                sizeBytes = entry.sizeBytes,
                linked = true,
                treeUri = entry.treeUri,
            )
        }
    }

    /**
     * Adds a game folder to the library WITHOUT copying it: the folder is
     * resolved to its real filesystem path and registered; the emulator
     * later reads the files in place. Returns LINKED on success.
     */
    fun linkFromTree(treeUri: Uri): LinkResult {
        val path = resolveTreeToPath(treeUri) ?: return LinkResult.UNSUPPORTED_LOCATION
        val dir = File(path)
        if (!dir.isDirectory || !dir.canRead()) {
            return LinkResult.NEEDS_PERMISSION
        }
        val eboot = findEboot(dir) ?: return LinkResult.NOT_A_GAME
        if (!eboot.canRead()) {
            return LinkResult.NEEDS_PERMISSION
        }
        val gameDir = eboot.parentFile ?: dir
        val sfo = File(gameDir, "sce_sys/param.sfo")
        val map = if (sfo.exists()) ParamSfo.parse(sfo) else emptyMap()

        val registry = readRegistry()?.toMutableList() ?: mutableListOf()
        /* re-linking the same folder updates the entry instead of duplicating */
        registry.removeAll { it.path == gameDir.absolutePath }
        registry.add(
            LinkedEntry(
                treeUri = treeUri.toString(),
                path = gameDir.absolutePath,
                title = ParamSfo.title(map).ifEmpty { gameDir.name },
                titleId = ParamSfo.titleId(map).ifEmpty { gameDir.name },
                appVersion = ParamSfo.appVersion(map),
                contentId = ParamSfo.contentId(map),
                category = ParamSfo.category(map),
                sizeBytes = gameDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            )
        )
        writeRegistry(registry)
        return LinkResult.LINKED
    }

    /** Removes a linked game from the library. Original files are kept. */
    fun unlink(game: GameInfo) {
        val registry = readRegistry()?.toMutableList() ?: return
        registry.removeAll { it.path == game.installDir.absolutePath }
        writeRegistry(registry)
        /* release the persisted SAF grant along with the entry */
        game.treeUri?.let { uriStr ->
            try {
                val uri = Uri.parse(uriStr)
                context.contentResolver.releasePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                /* grant already gone — fine */
            }
        }
    }

    /** The shared-storage "all files access" state (API 30+ gate). */
    fun allFilesAccessGranted(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /**
     * Resolves a SAF documents tree uri to the real filesystem path when the
     * tree is backed by local storage. Returns null for providers that are
     * not simple filesystem mirrors (cloud, downloads-provider shadows...).
     */
    private fun resolveTreeToPath(treeUri: Uri): String? {
        if (!isExternalStorageTree(treeUri)) {
            return null
        }
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return null
        }
        val sep = docId.indexOf(':')
        if (sep <= 0) {
            return null
        }
        val volume = docId.substring(0, sep)
        val rest = docId.substring(sep + 1)
        val base: File = when (volume) {
            "primary" -> Environment.getExternalStorageDirectory() /* /storage/emulated/0 */
            else -> File("/storage", volume) /* secondary volumes: /storage/<uuid> */
        }
        return if (rest.isBlank()) {
            base.absolutePath
        } else {
            File(base, rest).absolutePath
        }
    }

    private fun isExternalStorageTree(uri: Uri): Boolean {
        val auth = uri.authority ?: return false
        return auth == "com.android.externalstorage.documents" &&
            ("tree" == uri.pathSegments?.firstOrNull())
    }

    private data class LinkedEntry(
        val treeUri: String,
        val path: String,
        val title: String,
        val titleId: String,
        val appVersion: String,
        val contentId: String,
        val category: String,
        val sizeBytes: Long,
    )

    private fun readRegistry(): List<LinkedEntry>? {
        val f = linkedRegistry
        if (!f.exists()) {
            return null
        }
        return try {
            val json = JSONObject(f.readText())
            val arr = json.optJSONArray("games") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                LinkedEntry(
                    treeUri = o.optString("treeUri"),
                    path = o.optString("path"),
                    title = o.optString("title"),
                    titleId = o.optString("titleId"),
                    appVersion = o.optString("appVersion"),
                    contentId = o.optString("contentId"),
                    category = o.optString("category"),
                    sizeBytes = o.optLong("sizeBytes"),
                )
            }
        } catch (e: Exception) {
            /* corrupted registry: treat as empty rather than crashing the UI */
            null
        }
    }

    private fun writeRegistry(entries: List<LinkedEntry>) {
        try {
            linkedRegistry.parentFile?.mkdirs()
            val arr = org.json.JSONArray()
            entries.forEach {
                arr.put(
                    JSONObject()
                        .put("treeUri", it.treeUri)
                        .put("path", it.path)
                        .put("title", it.title)
                        .put("titleId", it.titleId)
                        .put("appVersion", it.appVersion)
                        .put("contentId", it.contentId)
                        .put("category", it.category)
                        .put("sizeBytes", it.sizeBytes)
                )
            }
            linkedRegistry.writeText(JSONObject().put("games", arr).toString())
        } catch (e: Exception) {
            /* storage full / IO error: the link is best-effort persisted */
        }
    }

    /** depth-limited eboot.bin lookup (games normally have it at the root). */
    private fun findEboot(dir: File, depth: Int = 2): File? {
        if (depth < 0) {
            return null
        }
        val direct = File(dir, "eboot.bin")
        if (direct.exists()) {
            return direct
        }
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val found = findEboot(child, depth - 1)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    fun delete(game: GameInfo): Boolean {
        val inner = game.installDir.deleteRecursively()
        /* nested layouts: the import shell dir (the SAF tree root) survives
         * the inner game dir — remove it too when now empty */
        val shell = game.installDir.parentFile
        if (shell != null && shell.isDirectory &&
            shell != gamesRoot && shell.listFiles()?.isEmpty() == true
        ) {
            shell.delete()
        }
        return inner
    }

    /**
     * Imports a game tree from a SAF document uri. Returns the installed
     * directory or null when no eboot.bin was found. [onProgress] receives
     * (filesCopied, bytesCopied) pairs.
     */
    fun importFromTree(treeUri: Uri, onProgress: (Int, Long) -> Unit = { _, _ -> }): File? {
        val resolver = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)

        val targetRoot = gamesRoot.apply { mkdirs() }
        var name = queryDisplayName(rootDocUri) ?: "game"
        name = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifEmpty { "game" }
        var target = File(targetRoot, name)
        var suffix = 1
        while (target.exists()) {
            target = File(targetRoot, "${name}_$suffix")
            suffix++
        }
        target.mkdirs()

        var files = 0
        var bytes = 0L
        val found = try {
            copyDocumentTree(rootDocUri, target) { f, b ->
                files = f
                bytes = b
                onProgress(f, b)
            }
        } catch (e: Exception) {
            /* provider died / grant revoked / storage full mid-copy: do not
             * leave an invisible partial tree behind */
            target.deleteRecursively()
            return null
        }
        if (!found) {
            /* no eboot.bin -> not a game: do not leave a partial copy around */
            target.deleteRecursively()
            return null
        }
        return target
    }

    private fun queryDisplayName(uri: Uri): String? =
        resolverQuery(uri)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else {
                null
            }
        }

    private fun resolverQuery(uri: Uri) = context.contentResolver.query(
        uri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        ),
        null, null, null
    )

    /** Copies the tree; returns true if an eboot.bin was found within it. */
    private fun copyDocumentTree(docUri: Uri, target: File,
                                 onProgress: (Int, Long) -> Unit): Boolean {
        var foundEboot = false
        var files = 0
        var bytes = 0L

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            docUri, DocumentsContract.getDocumentId(docUri)
        )
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                val mime = cursor.getString(1) ?: ""
                val docId = cursor.getString(2) ?: continue
                val size = cursor.getLong(3)

                if (name == "." || name == "..") {
                    continue
                }
                val childTarget = File(target, name)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    childTarget.mkdirs()
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(docUri, docId)
                    if (copyDocumentTree(childUri, childTarget, onProgress)) {
                        foundEboot = true
                    }
                } else {
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(docUri, docId)
                    try {
                        context.contentResolver.openInputStream(childUri)?.use { input ->
                            childTarget.outputStream().use { output ->
                                input.copyTo(output, 1 shl 16)
                            }
                        }
                        files++
                        bytes += size
                        onProgress(files, bytes)
                        if (name.equals("eboot.bin", ignoreCase = true)) {
                            foundEboot = true
                        }
                    } catch (e: Exception) {
                        // skip unreadable entries, continue the import
                    }
                }
            }
        }
        return foundEboot
    }
}
