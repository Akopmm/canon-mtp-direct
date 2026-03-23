package com.canon.cr3transfer.domain.usecase

import com.canon.cr3transfer.data.mtp.MtpDeviceManager
import com.canon.cr3transfer.data.mtp.MtpFileEnumerator
import com.canon.cr3transfer.domain.model.CameraFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

class ScanCameraUseCase @Inject constructor(
    private val deviceManager: MtpDeviceManager,
    private val fileEnumerator: MtpFileEnumerator,
) {
    operator fun invoke(): Flow<CameraFile> = flow {
        val device = deviceManager.device
            ?: throw IllegalStateException("MTP device not connected")
        val storageId = deviceManager.getStorageId()
            ?: throw IllegalStateException("No storage found on camera")
        emitAll(fileEnumerator.enumerateCameraFilesFlow(device, storageId))
    }.flowOn(Dispatchers.IO)
}
