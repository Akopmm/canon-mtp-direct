package com.akopmm.cr3transfer.domain.model

/**
 * File kinds the app imports from the camera. Photos (CR3 RAW, JPEG, and HEIF — the latter
 * produced when the EOS R8 shoots in HDR) route to the photo output dir; MP4 routes to video.
 */
enum class FileType {
    CR3, JPG, HEIF, MP4;

    val isVideo: Boolean get() = this == MP4
    val isPhoto: Boolean get() = !isVideo
}

data class CameraFile(
    val objectHandle: Int,
    val name: String,
    val sizeBytes: Long,
    val dateCreated: Long,
    val fileType: FileType,
)
