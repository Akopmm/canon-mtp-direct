package com.akopmm.cr3transfer.domain.imports

import com.akopmm.cr3transfer.domain.model.CameraFile
import com.akopmm.cr3transfer.domain.model.FileType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How imported files are grouped below the import folder. Both keep a per-camera folder. */
enum class FolderPattern(val label: String) {
    /** `<camera>/<day of the transfer>` — the original behaviour. */
    CAMERA_IMPORT_DATE("Camera / day of import"),

    /** `<camera>/<day the shot was taken>` — a two-day trip imported later still lands in two folders. */
    CAMERA_CAPTURE_DATE("Camera / day the photo was taken"),
}

/**
 * Where imports go. Roots are paths relative to the phone's primary shared storage
 * (`/storage/emulated/0`), without leading or trailing slashes. Pure Kotlin so it can be unit-tested;
 * [com.akopmm.cr3transfer.data.imports.ImportStore] maps it onto real directories.
 */
data class ImportLocation(
    val photoRoot: String = DEFAULT_PHOTO_ROOT,
    val videoRoot: String = DEFAULT_VIDEO_ROOT,
    val pattern: FolderPattern = FolderPattern.CAMERA_IMPORT_DATE,
) {
    fun rootFor(type: FileType): String = if (type.isVideo) videoRoot else photoRoot

    /**
     * Folder for [file] below its root: `<camera>/<yyyy-MM-dd>`, or just the date when the camera has
     * no name. The capture date falls back to [importDay] when the camera reported none (some older
     * bodies leave it empty).
     */
    fun subfolderFor(file: CameraFile, camera: String?, importDay: LocalDate, zone: ZoneId): String {
        val day = when (pattern) {
            FolderPattern.CAMERA_IMPORT_DATE -> importDay
            FolderPattern.CAMERA_CAPTURE_DATE ->
                if (file.dateCreated > 0) Instant.ofEpochMilli(file.dateCreated).atZone(zone).toLocalDate() else importDay
        }
        return if (camera.isNullOrBlank()) day.toString() else "$camera/$day"
    }

    /** Short human-readable summary, e.g. "DCIM/CanonImports (videos: Movies/CanonImports)". */
    val description: String
        get() = if (photoRoot == videoRoot) photoRoot else "$photoRoot (videos: $videoRoot)"

    companion object {
        const val DEFAULT_PHOTO_ROOT = "DCIM/CanonImports"
        const val DEFAULT_VIDEO_ROOT = "Movies/CanonImports"

        val DEFAULT = ImportLocation()
        val PICTURES = ImportLocation(photoRoot = "Pictures/CanonImports")

        /** A folder the user picked: photos and videos both go there. */
        fun custom(root: String, pattern: FolderPattern) = ImportLocation(root, root, pattern)

        /**
         * Turns a folder-picker tree id into a root, or null if the app can't write there with plain
         * file access: `primary:Photography/Raw` → `Photography/Raw`. Rejected: other volumes (SD
         * cards, USB drives), the storage root itself, `Android/…` (app-private on Android 11+) and
         * anything containing `..`.
         */
        fun rootFromTreeDocumentId(treeDocumentId: String): String? {
            if (!treeDocumentId.startsWith("primary:")) return null
            val segments = treeDocumentId.removePrefix("primary:")
                .split('/')
                .filter { it.isNotBlank() }
            if (segments.isEmpty()) return null
            if (segments.any { it == ".." || it == "." }) return null
            if (segments.first().equals("Android", ignoreCase = true)) return null
            return segments.joinToString("/")
        }

        /** Normalises a stored root ("/DCIM/CanonImports/" → "DCIM/CanonImports"); null if unusable. */
        fun normaliseRoot(root: String?): String? =
            root?.split('/')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() && ".." !in it }?.joinToString("/")
    }
}
