package com.akopmm.cr3transfer.data.imports

import com.akopmm.cr3transfer.domain.imports.LedgerEntry
import java.io.File

/**
 * Per-camera record of every file imported, kept independently of the files themselves: deleting the
 * phone copies (e.g. once they're synced to Immich) no longer makes the card offer them again.
 *
 * One plain-text file per camera, one `name<TAB>size<TAB>dateCreated` line per import, append-only.
 * Plain java.io so it can be unit-tested (org.json is an Android stub on the JVM).
 */
class ImportLedger(private val dir: File) {

    private fun fileFor(cameraId: String) = File(dir, "${cameraId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.tsv")

    fun exists(cameraId: String): Boolean = fileFor(cameraId).exists()

    /** Entries for [cameraId], grouped by file name. Malformed lines are skipped. */
    fun load(cameraId: String): Map<String, List<LedgerEntry>> {
        val file = fileFor(cameraId)
        if (!file.exists()) return emptyMap()
        return file.readLines()
            .mapNotNull { parse(it) }
            .groupBy { it.name }
    }

    fun append(cameraId: String, entries: Collection<LedgerEntry>) {
        if (entries.isEmpty()) return
        dir.mkdirs()
        fileFor(cameraId).appendText(entries.joinToString("") { format(it) + "\n" })
    }

    companion object {
        internal fun format(e: LedgerEntry): String =
            "${e.name.replace('\t', ' ').replace('\n', ' ')}\t${e.sizeBytes}\t${e.dateCreated}"

        /** Splits from the right, so only the name could ever contain odd characters. */
        internal fun parse(line: String): LedgerEntry? {
            val second = line.lastIndexOf('\t').takeIf { it > 0 } ?: return null
            val first = line.lastIndexOf('\t', second - 1).takeIf { it > 0 } ?: return null
            val size = line.substring(first + 1, second).toLongOrNull() ?: return null
            val date = line.substring(second + 1).toLongOrNull() ?: return null
            return LedgerEntry(line.substring(0, first), size, date)
        }
    }
}
