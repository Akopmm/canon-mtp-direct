package com.canon.cr3transfer.domain.model

data class TransferSession(
    val id: String,
    val dateMillis: Long,
    val transferred: Int,
    val skipped: Int,
    val failed: Int,
    val totalBytes: Long,
    val durationMs: Long,
)
