package com.akopmm.cr3transfer.domain.model

/**
 * File kinds the app imports from the camera.
 *
 * Two RAW kinds exist because Canon changed container with Digic 8: newer bodies (EOS R8, R,
 * M50, 90D…) write CR3, while everything before that (EOS 760D/Rebel T6s, 80D, 5D III…) writes
 * CR2. Those older bodies also record video as QuickTime .MOV rather than .MP4.
 *
 * Photos (RAW, JPEG, and HEIF — the latter produced when an R-series body shoots HDR) route to
 * the photo output dir; videos route to the video dir.
 */
enum class FileType {
    CR3, CR2, JPG, HEIF, MP4, MOV;

    val isVideo: Boolean get() = this == MP4 || this == MOV
    val isPhoto: Boolean get() = !isVideo

    /** RAW stills — the kinds that may need the embedded-preview thumbnail fallback. */
    val isRaw: Boolean get() = this == CR3 || this == CR2
}

data class CameraFile(
    val objectHandle: Int,
    val name: String,
    val sizeBytes: Long,
    val dateCreated: Long,
    val fileType: FileType,
)
