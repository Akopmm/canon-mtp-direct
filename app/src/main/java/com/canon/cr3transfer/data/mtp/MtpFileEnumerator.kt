package com.canon.cr3transfer.data.mtp

import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.util.Log
import com.canon.cr3transfer.domain.model.Cr3File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MtpFileEnumerator"

@Singleton
class MtpFileEnumerator @Inject constructor() {

    fun enumerateCr3Files(device: MtpDevice, storageId: Int): List<Cr3File> {
        val seen = mutableSetOf<Int>()
        val results = mutableListOf<Cr3File>()
        enumerateRecursive(device, storageId, parentHandle = 0, seen, results)
        Log.d(TAG, "Found ${results.size} unique CR3 files")
        return results
    }

    private fun enumerateRecursive(
        device: MtpDevice,
        storageId: Int,
        parentHandle: Int,
        seen: MutableSet<Int>,
        results: MutableList<Cr3File>,
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
                        Cr3File(
                            objectHandle = handle,
                            name = info.name,
                            sizeBytes = info.compressedSize.toLong(),
                            dateCreated = info.dateCreated,
                        )
                    )
                }
            }
        }
    }
}
