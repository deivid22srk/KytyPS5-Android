package dev.kytyps5.android.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** A game as the emulator understands it: a directory containing eboot.bin. */
data class GameInfo(
    val installDir: File,
    val title: String,
    val titleId: String,
    val appVersion: String,
    val contentId: String,
    val category: String,
    val sizeBytes: Long,
)

/**
 * Library management. Games are imported (copied) into app storage via SAF
 * because the emulator requires real POSIX paths; metadata comes from the
 * game's own sce_sys/param.sfo — never from placeholder data.
 */
class GameRepository(private val context: Context) {

    val gamesRoot: File
        get() = File(context.filesDir, "kyty/games")

    fun scan(): List<GameInfo> {
        val root = gamesRoot
        if (!root.exists()) {
            return emptyList()
        }
        return root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val eboot = findEboot(dir)
                if (eboot == null) {
                    return@mapNotNull null
                }
                val sfo = File(dir, "sce_sys/param.sfo")
                val map = if (sfo.exists()) ParamSfo.parse(sfo) else emptyMap()
                GameInfo(
                    installDir = dir,
                    title = ParamSfo.title(map).ifEmpty { dir.name },
                    titleId = ParamSfo.titleId(map).ifEmpty { dir.name },
                    appVersion = ParamSfo.appVersion(map),
                    contentId = ParamSfo.contentId(map),
                    category = ParamSfo.category(map),
                    sizeBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                )
            }
            ?.sortedBy { it.title.lowercase() }
            ?: emptyList()
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

    fun delete(game: GameInfo): Boolean = game.installDir.deleteRecursively()

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
        val found = copyDocumentTree(rootDocUri, target) { f, b ->
            files = f
            bytes = b
            onProgress(f, b)
        }
        return if (found) target else null
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
