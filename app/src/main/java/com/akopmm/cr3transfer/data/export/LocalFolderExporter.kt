package com.akopmm.cr3transfer.data.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream
import java.util.Locale

/** A destination folder. Implemented over SAF in production and over java.io.File in tests. */
interface ExportDirectory {
    /** Existing child file called [name], or null. */
    fun findFile(name: String): ExportFile?

    /** Existing child directory called [name], or a newly created one; null if that's impossible. */
    fun findOrCreateDirectory(name: String): ExportDirectory?

    /** Creates an empty file. The provider may pick a different name if [name] is taken. */
    fun createFile(name: String, mimeType: String): ExportFile?
}

interface ExportFile {
    /** Opens for writing, truncating anything already there. */
    fun openOutput(): OutputStream?
    fun length(): Long
    fun delete(): Boolean
}

data class ExportProgress(val done: Int, val total: Int, val currentFile: String?)

data class ExportResult(
    /** Files now present at the destination with the same size as the source (copied or already there). */
    val verified: Int,
    /** Of [verified], how many were skipped because an identical-size copy already existed. */
    val alreadyPresent: Int,
    val failed: List<String>,
    /** Source folders deleted afterwards (only ever folders whose every file was verified). */
    val clearedFolders: List<File>,
)

/**
 * Copies import folders (DCIM/CanonImports/…) to a user-chosen folder.
 *
 * Deleting the sources ("auto-clear") is the dangerous part, so it is only done per folder and only
 * when every file of that folder was verified at the destination by size. A failed, short or
 * cancelled copy never leads to a deletion; partial destination files are removed.
 */
class LocalFolderExporter {

    suspend fun export(
        folders: List<File>,
        root: ExportDirectory,
        clearSources: Boolean,
        onProgress: (ExportProgress) -> Unit = {},
    ): ExportResult {
        val plan = folders.map { folder ->
            folder to folder.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.name }.toList()
        }
        val total = plan.sumOf { it.second.size }
        var done = 0
        var verified = 0
        var alreadyPresent = 0
        val failed = mutableListOf<String>()
        val cleared = mutableListOf<File>()

        for ((folder, files) in plan) {
            val dest = root.findOrCreateDirectory(destinationDirName(folder))
            var folderOk = dest != null
            for (file in files) {
                currentCoroutineContext().ensureActive()
                onProgress(ExportProgress(done, total, file.name))
                val outcome = if (dest == null) Outcome.FAILED else copyOne(file, dest)
                when (outcome) {
                    Outcome.COPIED -> verified++
                    Outcome.ALREADY_PRESENT -> { verified++; alreadyPresent++ }
                    Outcome.FAILED -> { failed += file.path; folderOk = false }
                }
                done++
            }
            if (clearSources && folderOk && folder.exists() && folder.deleteRecursively()) {
                cleared += folder
            }
        }
        onProgress(ExportProgress(done, total, null))
        return ExportResult(verified, alreadyPresent, failed, cleared)
    }

    private enum class Outcome { COPIED, ALREADY_PRESENT, FAILED }

    private suspend fun copyOne(file: File, dest: ExportDirectory): Outcome {
        val size = file.length()
        val existing = dest.findFile(file.name)
        if (existing != null && existing.length() == size) return Outcome.ALREADY_PRESENT
        val target = existing ?: dest.createFile(file.name, mimeTypeFor(file)) ?: return Outcome.FAILED
        return try {
            val out = target.openOutput() ?: throw java.io.IOException("cannot open ${file.name} for writing")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            if (target.length() == size) {
                Outcome.COPIED
            } else {
                target.delete()
                Outcome.FAILED
            }
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            target.delete()
            Outcome.FAILED
        }
    }

    companion object {
        /** `CanonImports/<camera>/<date>` → `<camera>_<date>`; a folder directly under CanonImports keeps its name. */
        fun destinationDirName(folder: File): String {
            val parent = folder.parentFile
            return if (parent != null && parent.name != "CanonImports") "${parent.name}_${folder.name}" else folder.name
        }

        fun mimeTypeFor(file: File): String = when (file.extension.uppercase(Locale.US)) {
            "CR3" -> "image/x-canon-cr3"
            "CR2" -> "image/x-canon-cr2"
            "JPG", "JPEG" -> "image/jpeg"
            "HIF", "HEIF", "HEIC" -> "image/heif"
            "MP4" -> "video/mp4"
            "MOV" -> "video/quicktime"
            else -> "application/octet-stream"
        }
    }
}
