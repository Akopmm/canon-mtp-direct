package com.canon.cr3transfer.domain.model

data class Cr3File(
    val objectHandle: Int,
    val name: String,
    val sizeBytes: Long,
    val dateCreated: Long,
)
