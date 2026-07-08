package com.akopmm.cr3transfer.data.mtp

import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.mtp.MtpObjectInfo
import android.os.Build
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
            
            val info = safeGetObjectInfo(device, handle) ?: continue
            if (info.format == MtpConstants.FORMAT_ASSOCIATION) {
                folders += handle
                continue
            }
            val file = toCameraFile(handle, info) ?: continue
            foundFiles = true
            Log.d(TAG, "Flat: ${file.fileType} ${file.name} (size=${file.sizeBytes})")
            emit(file)
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
            
            val info = safeGetObjectInfo(device, handle)
            if (info == null) {
                Log.d(TAG, "  -> getObjectInfo returned null for handle $handle")
                continue
            }
            Log.d(TAG, "  -> Got info for handle $handle: name=${info.name} format=${info.format}")
            if (info.format == MtpConstants.FORMAT_ASSOCIATION) {
                Log.d(TAG, "  -> Entering folder: ${info.name}")
                emitAll(recurse(device, storageId, handle, visitedParents, depth + 1))
                continue
            }
            val file = toCameraFile(handle, info) ?: continue
            filesFound++
            Log.d(TAG, "  -> Found ${file.fileType}: ${file.name} (size=${file.sizeBytes})")
            emit(file)
        }
        Log.d(TAG, "recurse: completed for parent 0x${parentHandle.toString(16)}, found $filesFound files")
    }

    /**
     * Reads the object size safely. [MtpObjectInfo.getCompressedSize] returns a 32-bit int and
     * throws IllegalStateException for files larger than 2GB (Integer.MAX_VALUE) — which silently
     * dropped large videos. The 64-bit [MtpObjectInfo.getCompressedSizeLong] (API 29+) returns the
     * real size; on API 26–28 we fall back to the unsigned 32-bit value (capped at ~4GB).
     */
    private fun readSize(info: MtpObjectInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.compressedSizeLong
        } else {
            try {
                info.compressedSize.toLong() and 0xFFFFFFFFL
            } catch (e: Exception) {
                Log.w(TAG, "compressedSize overflow on API<29 (size unknown): ${e.message}")
                0L
            }
        }
    }

    /**
     * getObjectInfo with one retry. When a huge (>4GB) video is on the card the R8's MTP
     * responder intermittently corrupts handles, so a first call can throw or return null while
     * a retry succeeds. Logs the exception TYPE (not just message, which is often null) so we
     * can see what actually fails for the problematic file.
     */
    private fun safeGetObjectInfo(device: MtpDevice, handle: Int): MtpObjectInfo? {
        repeat(2) { attempt ->
            try {
                val info = device.getObjectInfo(handle)
                if (info != null) return info
                Log.d(TAG, "getObjectInfo null for handle $handle (attempt ${attempt + 1})")
            } catch (e: Throwable) {
                Log.w(TAG, "getObjectInfo threw ${e.javaClass.simpleName} for handle $handle (attempt ${attempt + 1}): ${e.message}", e)
            }
        }
        return null
    }

    /**
     * Builds a [CameraFile] for CR3/MP4 objects, reading each field defensively. A corrupted
     * ObjectInfo for a huge file can throw when its size/date fields are accessed; we log the
     * exception type and skip rather than letting it bubble up and silently drop the file.
     * Size is read as unsigned uint32 so 2–4GB videos don't show a negative size.
     */
    private fun toCameraFile(handle: Int, info: MtpObjectInfo): CameraFile? {
        return try {
            val name = info.name ?: return null
            val type = when {
                name.endsWith(".CR3", ignoreCase = true) -> FileType.CR3
                name.endsWith(".JPG", ignoreCase = true) ||
                    name.endsWith(".JPEG", ignoreCase = true) -> FileType.JPG
                // Canon writes HEIF (HDR PQ) shots with a .HIF extension; accept .HEIF/.HEIC too.
                name.endsWith(".HIF", ignoreCase = true) ||
                    name.endsWith(".HEIF", ignoreCase = true) ||
                    name.endsWith(".HEIC", ignoreCase = true) -> FileType.HEIF
                name.endsWith(".MP4", ignoreCase = true) -> FileType.MP4
                else -> return null
            }
            CameraFile(
                objectHandle = handle,
                name = name,
                sizeBytes = readSize(info),
                dateCreated = info.dateCreated,
                fileType = type,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "toCameraFile threw ${e.javaClass.simpleName} for handle $handle (name=${runCatching { info.name }.getOrNull()}): ${e.message}", e)
            null
        }
    }
}
