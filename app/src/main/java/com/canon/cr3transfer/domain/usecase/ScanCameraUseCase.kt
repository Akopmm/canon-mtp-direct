package com.canon.cr3transfer.domain.usecase

import com.canon.cr3transfer.data.mtp.MtpDeviceManager
import com.canon.cr3transfer.data.mtp.MtpFileEnumerator
import com.canon.cr3transfer.domain.model.Cr3File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ScanCameraUseCase @Inject constructor(
    private val deviceManager: MtpDeviceManager,
    private val fileEnumerator: MtpFileEnumerator,
) {
    suspend operator fun invoke(): List<Cr3File> = withContext(Dispatchers.IO) {
        val device = deviceManager.device
            ?: throw IllegalStateException("MTP device not connected")
        val storageId = deviceManager.getStorageId()
            ?: throw IllegalStateException("No storage found on camera")
        fileEnumerator.enumerateCr3Files(device, storageId)
    }
}
