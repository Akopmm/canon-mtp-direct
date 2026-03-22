package com.canon.cr3transfer.data.mtp

import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.util.Log
import com.canon.cr3transfer.domain.model.CameraFile
import com.canon.cr3transfer.domain.model.FileType
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MtpFileEnumerator"

@Singleton
class MtpFileEnumerator @Inject constructor() {

    fun enumerateCameraFiles(device: MtpDevice, storageId: Int): List<CameraFile> {
        val seen = mutableSetOf<Int>()
        val results = mutableListOf<CameraFile>()
        enumerateRecursive(device, storageId, parentHandle = 0, seen, results)
        Log.d(TAG, "Found ${results.size} camera files (CR3+MP4)")
        return results
    }

    private fun enumerateRecursive(
        device: MtpDevice,
        storageId: Int,
        parentHandle: Int,
        seen: MutableSet<Int>,
        results: MutableList<CameraFile>,
    ) {
        val handles = device.getObjectHandles(storageId, 0, parentHandle) ?: return
        for (handle in handles) {
            if (!seen.add(handle)) continue
            val info = device.getObjectInfo(handle) ?: continue
            when {
                info.format == MtpConstants.FORMAT_ASSOCIATION -> {
                    Log.d(TAG, "Entering folder: ${info.name} (handle=0x${handle.toString(16)})")
                    enumerateRecursive(device, storageId, handle, seen, results)
                }
                info.name.endsWith(".CR3", ignoreCase = true) -> {
                    Log.d(TAG, "Found CR3: ${info.name} (${info.compressedSize} bytes)")
                    results.add(
                        CameraFile(
                            objectHandle = handle,
                            name = info.name,
                            sizeBytes = info.compressedSize.toLong(),
                            dateCreated = info.dateCreated,
                            fileType = FileType.CR3,
                        )
                    )
                }
                info.name.endsWith(".MP4", ignoreCase = true) -> {
                    Log.d(TAG, "Found MP4: ${info.name} (${info.compressedSize} bytes)")
                    results.add(
                        CameraFile(
                            objectHandle = handle,
                            name = info.name,
                            sizeBytes = info.compressedSize.toLong(),
                            dateCreated = info.dateCreated,
                            fileType = FileType.MP4,
                        )
                    )
                }
            }
        }
    }
}
