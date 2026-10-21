package com.akopmm.cr3transfer.data.export

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream

/**
 * [ExportDirectory] over a Storage Access Framework tree. Children are listed once and cached:
 * DocumentFile.findFile() queries the provider on every call, which is O(n²) for a folder of RAWs.
 */
class DocumentFileExportDirectory(
    private val resolver: ContentResolver,
    private val doc: DocumentFile,
) : ExportDirectory {

    private val children: MutableMap<String, DocumentFile> by lazy {
        doc.listFiles().filter { it.name != null }.associateByTo(mutableMapOf()) { it.name!! }
    }

    override fun findFile(name: String): ExportFile? =
        children[name]?.takeIf { it.isFile }?.let { DocumentExportFile(resolver, it) }

    override fun findOrCreateDirectory(name: String): ExportDirectory? {
        val dir = children[name]?.takeIf { it.isDirectory }
            ?: doc.createDirectory(name)?.also { children[it.name ?: name] = it }
            ?: return null
        return DocumentFileExportDirectory(resolver, dir)
    }

    override fun createFile(name: String, mimeType: String): ExportFile? =
        doc.createFile(mimeType, name)
            ?.also { children[it.name ?: name] = it }
            ?.let { DocumentExportFile(resolver, it) }
}

private class DocumentExportFile(
    private val resolver: ContentResolver,
    private val doc: DocumentFile,
) : ExportFile {
    // "wt": plain "w" does not truncate on some providers, leaving the tail of a longer old file.
    override fun openOutput(): OutputStream? = resolver.openOutputStream(doc.uri, "wt")
    override fun length(): Long = doc.length()
    override fun delete(): Boolean = doc.delete()
}
