package com.akopmm.cr3transfer.domain.usecase

import android.util.Log
import com.akopmm.cr3transfer.data.mtp.MtpDeviceManager
import com.akopmm.cr3transfer.data.mtp.MtpFileEnumerator
import com.akopmm.cr3transfer.domain.model.CameraFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        
        // Retry getting storage ID up to 3 times with 500ms delay
        // This handles race condition where camera is still initializing
        var storageId: Int? = null
        for (attempt in 1..3) {
            storageId = deviceManager.getStorageId()
            if (storageId != null) {
                Log.d("CR3Transfer", "Storage found on attempt $attempt")
                break
            }
            if (attempt < 3) {
                Log.w("CR3Transfer", "Storage not ready on attempt $attempt, retrying...")
                delay(500L)
            }
        }
        
        storageId ?: throw IllegalStateException("No storage found on camera after retries")
        
        emitAll(fileEnumerator.enumerateCameraFilesFlow(device, storageId))
    }.flowOn(Dispatchers.IO)
}
