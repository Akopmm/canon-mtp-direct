package com.akopmm.cr3transfer.data.mtp

import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.util.Log
import com.akopmm.cr3transfer.domain.model.CameraFile
import com.akopmm.cr3transfer.domain.model.FileType
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
        // Don't use global seen set - it prevents processing handles at different nesting levels
        // Instead use depth limit to prevent infinite loops from circular references
        val visitedParents = mutableSetOf<Int>()

        val topHandles = device.getObjectHandles(storageId, 0, 0) ?: run {
            Log.w(TAG, "getObjectHandles returned null at root")
            return@flow
        }

        var foundFiles = false
        val folders = mutableListOf<Int>()

        for (handle in topHandles) {
            // Don't skip negative handles at root level - they might be valid despite the negative encoding
            // Just log them for debugging
            if (handle < 0) {
                Log.d(TAG, "Root handle is negative: $handle (0x${handle.toString(16)})")
            }
            
            try {
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
            } catch (e: Exception) {
                Log.w(TAG, "Error getting info for handle $handle: ${e.message}")
                // Continue with next file instead of failing entirely
            }
        }

        if (!foundFiles) {
            // Flat call returned only folders — recurse into each one
            Log.d(TAG, "Flat enumeration found no files, falling back to recursive with ${folders.size} valid folders")
            
            // If no folders were identified but we got handles, try to recurse into even corrupted-looking handles
            // The negative encoding might be a quirk of huge files but the folder structure could still be accessible
            val handlesTryRecurse: List<Int> = if (folders.isEmpty()) {
                Log.w(TAG, "No valid folders at root, attempting to recurse into all root handles as potential folders")
                topHandles.toList()
            } else {
                folders
            }
            
            for (folderHandle in handlesTryRecurse) {
                Log.d(TAG, "Recursing into handle: 0x${folderHandle.toString(16)}")
                emitAll(recurse(device, storageId, folderHandle, visitedParents, 0))
            }
        }
    }

    private fun recurse(
        device: MtpDevice,
        storageId: Int,
        parentHandle: Int,
        visitedParents: MutableSet<Int>,
        depth: Int,
    ): Flow<CameraFile> = flow {
        // Depth limit to prevent infinite recursion on circular references (from corrupted handles)
        if (depth > 10) {
            Log.w(TAG, "recurse: depth limit (10) exceeded for parent 0x${parentHandle.toString(16)}, stopping")
            return@flow
        }
        
        // Prevent cycles: don't recurse into the same parent multiple times
        if (!visitedParents.add(parentHandle)) {
            Log.d(TAG, "recurse: parent 0x${parentHandle.toString(16)} already visited, skipping")
            return@flow
        }
        
        Log.d(TAG, "recurse() called for parentHandle: 0x${parentHandle.toString(16)} (depth=$depth)")
        val handles = device.getObjectHandles(storageId, 0, parentHandle)
        if (handles == null) {
            Log.w(TAG, "getObjectHandles returned null in recurse for parent 0x${parentHandle.toString(16)}")
            return@flow
        }
        Log.d(TAG, "recurse: got ${handles.size} handles for parent 0x${parentHandle.toString(16)}")
        var filesFound = 0
        for (handle in handles) {
            // Log raw handle value
            Log.d(TAG, "  Raw handle received: $handle (0x${handle.toString(16)}) in parent 0x${parentHandle.toString(16)}")
            
            // Don't skip negative handles - they might be valid despite the encoding
            // When huge video corrupts MTP, all handles become negative but still work
            if (handle < 0) {
                Log.d(TAG, "  -> Handle is negative but attempting getObjectInfo anyway")
            }
            
            try {
                val info = device.getObjectInfo(handle)
                if (info == null) {
                    Log.d(TAG, "  -> getObjectInfo returned null for handle $handle")
                    continue
                }
                Log.d(TAG, "  -> Got info for handle $handle: name=${info.name} format=${info.format}")
                when {
                    info.format == MtpConstants.FORMAT_ASSOCIATION -> {
                        Log.d(TAG, "  -> Entering folder: ${info.name}")
                        emitAll(recurse(device, storageId, handle, visitedParents, depth + 1))
                    }
                    info.name.endsWith(".CR3", ignoreCase = true) -> {
                        filesFound++
                        Log.d(TAG, "  -> Found CR3: ${info.name}")
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
                        filesFound++
                        Log.d(TAG, "  -> Found MP4: ${info.name}")
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
            } catch (e: Exception) {
                Log.w(TAG, "  -> Exception getting info for handle $handle: ${e.message}")
                // Continue with next file instead of failing entirely
            }
        }
        Log.d(TAG, "recurse: completed for parent 0x${parentHandle.toString(16)}, found $filesFound files")
    }
}
