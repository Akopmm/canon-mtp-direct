package com.canon.cr3transfer.domain.model

enum class FileType { CR3, MP4 }

data class CameraFile(
    val objectHandle: Int,
    val name: String,
    val sizeBytes: Long,
    val dateCreated: Long,
    val fileType: FileType,
)
