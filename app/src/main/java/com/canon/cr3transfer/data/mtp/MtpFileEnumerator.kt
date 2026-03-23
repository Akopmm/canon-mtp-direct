package com.canon.cr3transfer.data.mtp

import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.util.Log
import com.canon.cr3transfer.domain.model.CameraFile
import com.canon.cr3transfer.domain.model.FileType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MtpFileEnumerator"

@Singleton
class MtpFileEnumerator @Inject constructor() {

    /**
     * Streams [CameraFile] objects as they are discovered on the camera.
     *
     * Attempts flat enumeration first: calls getObjectHandles with parentHandle=0
     * (MTP_OBJECT_HANDLE_ALL). On firmware that returns all storage objects in one
     * call, no recursion is needed. If the call returns only folders (EOS R8 treats
     * parentHandle=0 as root-level only), falls back to the recursive algorithm so
     * the caller always gets correct results.
     */
    fun enumerateCameraFilesFlow(device: MtpDevice, storageId: Int): Flow<CameraFile> = flow {
        val seen = mutableSetOf<Int>()

        val topHandles = device.getObjectHandles(storageId, 0, 0) ?: run {
            Log.w(TAG, "getObjectHandles returned null at root")
            return@flow
        }

        var foundFiles = false
        val folders = mutableListOf<Int>()

        for (handle in topHandles) {
            if (!seen.add(handle)) continue
            val info = device.getObjectInfo(handle) ?: continue
            when {
                info.format == MtpConstants.FORMAT_ASSOCIATION -> {
                    folders += handle
                }
                info.name.endsWith(".CR3", ignoreCase = true) -> {
                    foundFiles = true
                    Log.d(TAG, "Flat: CR3 ${info.name}")
                    emit(
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
                    foundFiles = true
                    Log.d(TAG, "Flat: MP4 ${info.name}")
                    emit(
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

        if (!foundFiles) {
            // Flat call returned only folders — recurse into each one
            Log.d(TAG, "Flat enumeration found no files, falling back to recursive")
            for (folderHandle in folders) {
                emitAll(recurse(device, storageId, folderHandle, seen))
            }
        }
    }

    private fun recurse(
        device: MtpDevice,
        storageId: Int,
        parentHandle: Int,
        seen: MutableSet<Int>,
    ): Flow<CameraFile> = flow {
        val handles = device.getObjectHandles(storageId, 0, parentHandle) ?: return@flow
        for (handle in handles) {
            if (!seen.add(handle)) continue
            val info = device.getObjectInfo(handle) ?: continue
            when {
                info.format == MtpConstants.FORMAT_ASSOCIATION -> {
                    Log.d(TAG, "Entering folder: ${info.name}")
                    emitAll(recurse(device, storageId, handle, seen))
                }
                info.name.endsWith(".CR3", ignoreCase = true) -> {
                    Log.d(TAG, "Found CR3: ${info.name}")
                    emit(
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
                    Log.d(TAG, "Found MP4: ${info.name}")
                    emit(
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
