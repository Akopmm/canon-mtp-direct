package com.canon.cr3transfer.domain.model

sealed interface TransferState {
    data object Idle : TransferState
    data object CameraConnected : TransferState
    data object Scanning : TransferState
    data class FilePicker(
        val files: List<Cr3File>,
        val selectedHandles: Set<Int>,
    ) : TransferState

    data class Transferring(
        val totalFiles: Int,
        val completedFiles: Int,
        val currentFileName: String,
        val fileStatuses: List<FileTransferStatus>,
    ) : TransferState

    data class Done(
        val transferred: Int,
        val skipped: Int,
        val failed: Int,
    ) : TransferState

    data class Error(
        val message: String,
        val isCameraSetupError: Boolean = false,
    ) : TransferState
}

data class FileTransferStatus(
    val fileName: String,
    val sizeMb: Double,
    val status: FileStatus,
)

enum class FileStatus {
    PENDING,
    TRANSFERRING,
    DONE,
    SKIPPED,
    ERROR,
}
