package com.akopmm.cr3transfer.domain.imports

import com.akopmm.cr3transfer.domain.model.CameraFile
import com.akopmm.cr3transfer.domain.model.FileType

/** One file imported from a camera, as the camera described it. */
data class LedgerEntry(val name: String, val sizeBytes: Long, val dateCreated: Long) {
    companion object {
        fun of(file: CameraFile) = LedgerEntry(file.name, file.sizeBytes, file.dateCreated)
    }
}

/** Imported files currently on disk: name → the sizes of the files carrying that name. */
data class ImportedFiles(
    val photos: Map<String, Set<Long>>,
    val videos: Map<String, Set<Long>>,
) {
    fun sizesOf(name: String, type: FileType): Set<Long>? = (if (type.isVideo) videos else photos)[name]

    fun entries(): List<LedgerEntry> =
        (photos.entries + videos.entries).flatMap { (name, sizes) -> sizes.map { LedgerEntry(name, it, 0L) } }

    companion object {
        val EMPTY = ImportedFiles(emptyMap(), emptyMap())
    }
}

/**
 * Decides whether a file on the card was imported before.
 *
 * File names alone are not enough: Canon's counter wraps after 9999 (and restarts when the numbering
 * mode or card changes), so a brand-new IMG_0001.CR3 can share its name with an old one. A file
 * therefore only counts as imported when the name AND the size AND the capture time agree, each
 * compared only when both sides know it.
 */
object DedupPolicy {
    /** MTP's ObjectInfo size field is 32-bit: objects of 4 GB and more report 0xFFFFFFFF. */
    const val UNKNOWN_SIZE = 0xFFFFFFFFL

    /** Imported before, according to the ledger or the files on disk — used to pre-select new shots. */
    fun wasImported(file: CameraFile, ledger: Map<String, List<LedgerEntry>>, onDisk: ImportedFiles): Boolean =
        ledger[file.name].orEmpty().any { matches(it, file) } || isOnDisk(file, onDisk)

    /** An identical file is already in an import folder — the transfer skips these to avoid duplicates. */
    fun isOnDisk(file: CameraFile, onDisk: ImportedFiles): Boolean {
        val sizes = onDisk.sizesOf(file.name, file.fileType) ?: return false
        return !sizeKnown(file.sizeBytes) || file.sizeBytes in sizes
    }

    private fun matches(entry: LedgerEntry, file: CameraFile): Boolean {
        val sizeOk = !sizeKnown(entry.sizeBytes) || !sizeKnown(file.sizeBytes) || entry.sizeBytes == file.sizeBytes
        val dateOk = entry.dateCreated <= 0 || file.dateCreated <= 0 || entry.dateCreated == file.dateCreated
        return sizeOk && dateOk
    }

    private fun sizeKnown(size: Long) = size > 0 && size != UNKNOWN_SIZE

    /** `IMG_0001.CR3` → `IMG_0001_1.CR3`, `_2`, … — the first name [taken] says is free. */
    fun freeName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        return generateSequence(1) { it + 1 }.map { "${stem}_$it$ext" }.first { !taken(it) }
    }
}
