package com.akopmm.cr3transfer.data.imports

import com.akopmm.cr3transfer.domain.imports.ImportLocation
import com.akopmm.cr3transfer.domain.imports.ImportedFiles
import com.akopmm.cr3transfer.domain.model.CameraFile
import com.akopmm.cr3transfer.domain.model.FileType
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Every import root ever used, so changing the folder never makes old imports look new. */
data class KnownRoots(val photo: Set<String>, val video: Set<String>)

/**
 * The file-system side of imports, on plain java.io.File so it can be tested against a temp folder.
 * [storageRoot] is primary shared storage (`/storage/emulated/0`); [fallbackRoot] is the app's own
 * external files dir, used when the public folder can't be written (no "All files access").
 */
class ImportFolders(
    private val storageRoot: File,
    private val fallbackRoot: File,
) {
    /** Creates and returns the directory [file] is imported into. */
    fun destDirFor(
        location: ImportLocation,
        file: CameraFile,
        camera: String?,
        importDay: LocalDate,
        zone: ZoneId,
    ): File {
        val subfolder = location.subfolderFor(file, camera, importDay, zone)
        val public = File(storageRoot, "${location.rootFor(file.fileType)}/$subfolder")
        if (isWritableDir(public)) return public
        return File(fallbackDir(file.fileType), subfolder).apply { mkdirs() }
    }

    /**
     * Files already imported from [camera], across every known root and the fallback dir, with their
     * sizes. Scoped to the camera's folder (when it has one) so two bodies that both shoot
     * IMG_0001.CR3 don't shadow each other.
     */
    fun importedFiles(roots: KnownRoots, camera: String?): ImportedFiles {
        fun index(rootDirs: List<File>): Map<String, Set<Long>> = rootDirs
            .map { if (camera.isNullOrBlank()) it else File(it, camera) }
            .distinct()
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") } }
            .groupBy({ it.name }, { it.length() })
            .mapValues { it.value.toSet() }

        return ImportedFiles(
            photos = index(roots.photo.map { File(storageRoot, it) } + fallbackDir(FileType.CR3)),
            videos = index(roots.video.map { File(storageRoot, it) } + fallbackDir(FileType.MP4)),
        )
    }

    /** Folders below the photo roots holding at least one RAW file directly, newest first. */
    fun importFolders(photoRoots: Collection<String>): List<File> =
        (photoRoots.map { File(storageRoot, it) } + fallbackDir(FileType.CR3))
            .distinct()
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown().filter { dir ->
                    dir.isDirectory && dir != root && dir.listFiles()?.any { it.isRawImport() } == true
                }
            }
            .distinct()
            .sortedWith(compareByDescending<File> { it.name }.thenByDescending { it.parentFile?.name })

    private fun fallbackDir(type: FileType): File =
        File(fallbackRoot, if (type.isVideo) "CanonImports/videos" else "CanonImports/photos")

    private fun isWritableDir(dir: File): Boolean {
        if (!dir.mkdirs() && !dir.isDirectory) return false
        return try {
            val probe = File(dir, ".write_test")
            probe.createNewFile()
            probe.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** RAW stills as written to disk: CR3 from Digic 8 and newer bodies, CR2 from the ones before. */
        private val RAW_EXTENSIONS = listOf(".CR3", ".CR2")

        fun File.isRawImport(): Boolean = isFile && RAW_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }
    }
}
