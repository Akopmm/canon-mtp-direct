package com.akopmm.cr3transfer.domain.usecase

import com.akopmm.cr3transfer.data.mtp.MtpDeviceManager
import com.akopmm.cr3transfer.data.mtp.MtpTransferRepository
import com.akopmm.cr3transfer.data.mtp.TransferProgress
import com.akopmm.cr3transfer.domain.model.CameraFile
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class TransferFilesUseCase @Inject constructor(
    private val deviceManager: MtpDeviceManager,
    private val transferRepository: MtpTransferRepository,
) {
    operator fun invoke(files: List<CameraFile>, deleteAfterTransfer: Boolean = false): Flow<TransferProgress> {
        val device = deviceManager.device
            ?: throw IllegalStateException("MTP device not connected")
        return transferRepository.transferFiles(device, files, deleteAfterTransfer)
    }
}
