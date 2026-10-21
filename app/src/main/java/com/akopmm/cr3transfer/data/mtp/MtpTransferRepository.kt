package com.akopmm.cr3transfer.data.mtp

import android.content.Context
import android.media.MediaScannerConnection
import android.mtp.MtpDevice
import android.util.Log
import com.akopmm.cr3transfer.data.imports.ImportStore
import com.akopmm.cr3transfer.domain.model.CameraFile
import com.akopmm.cr3transfer.domain.model.FileStatus
import com.akopmm.cr3transfer.domain.model.FileTransferStatus
import com.akopmm.cr3transfer.domain.model.FileType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MtpTransferRepo"

data class TransferProgress(
    val totalFiles: Int,
    val completedFiles: Int,
    val currentFileName: String,
    val fileStatuses: List<FileTransferStatus>,
)

@Singleton
class MtpTransferRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val importStore: ImportStore,
) {
    fun transferFiles(
        device: MtpDevice,
        files: List<CameraFile>,
        deleteAfterTransfer: Boolean = false,
    ): Flow<TransferProgress> = flow {
        val importDay = LocalDate.now()
        Log.d(TAG, "Starting transfer on $importDay")

        // Build the dedup cache once instead of walking the filesystem per file
        val importedCache = importStore.importedNames()

        val statuses = files.map { file ->
            FileTransferStatus(
                fileName = file.name,
                sizeMb = file.sizeBytes / (1024.0 * 1024.0),
                status = FileStatus.PENDING,
            )
        }.toMutableList()

        var completed = 0

        for ((index, file) in files.withIndex()) {
            val destDir = importStore.destDirFor(file, importDay)
            val destFile = File(destDir, file.name)
            val alreadyTransferred = importedCache.contains(file.name, file.fileType)
            Log.d(TAG, "File ${file.name}: alreadyImported=$alreadyTransferred")

            if (alreadyTransferred) {
                statuses[index] = statuses[index].copy(status = FileStatus.SKIPPED)
                completed++
                emit(TransferProgress(files.size, completed, file.name, statuses.toList()))
                continue
            }

            statuses[index] = statuses[index].copy(status = FileStatus.TRANSFERRING)
            emit(TransferProgress(files.size, completed, file.name, statuses.toList()))

            val success = withContext(Dispatchers.IO) {
                try {
                    if (!destDir.exists()) destDir.mkdirs()
                    if (!destDir.isDirectory) {
                        Log.e(TAG, "Destination directory could not be created: ${destDir.absolutePath}")
                        return@withContext false
                    }
                    Log.d(TAG, "Importing ${file.name} (handle=0x${file.objectHandle.toString(16)}) to ${destFile.absolutePath}")
                    val imported = device.importFile(file.objectHandle, destFile.absolutePath)
                    Log.d(TAG, "importFile returned: $imported, file exists: ${destFile.exists()}, size: ${destFile.length()}")
                    if (imported && destFile.exists() && destFile.length() > 0) {
                        val mimeType = when (file.fileType) {
                            FileType.CR3 -> "image/x-canon-cr3"
                            FileType.CR2 -> "image/x-canon-cr2"
                            FileType.JPG -> "image/jpeg"
                            FileType.HEIF -> "image/heif"
                            FileType.MP4 -> "video/mp4"
                            FileType.MOV -> "video/quicktime"
                        }
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(destFile.absolutePath),
                            arrayOf(mimeType),
                            null,
                        )
                        true
                    } else {
                        Log.e(TAG, "importFile failed for ${file.name}")
                        destFile.delete()
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Transfer failed for ${file.name}", e)
                    destFile.delete()
                    false
                }
            }

            if (success) {
                statuses[index] = statuses[index].copy(status = FileStatus.DONE)
                Log.d(TAG, "Successfully transferred ${file.name} (${destFile.length()} bytes)")
                if (deleteAfterTransfer) {
                    try {
                        device.deleteObject(file.objectHandle)
                        Log.d(TAG, "Deleted ${file.name} from camera")
                    } catch (e: Exception) {
                        Log.w(TAG, "deleteObject failed for ${file.name}: ${e.message}")
                    }
                }
            } else {
                statuses[index] = statuses[index].copy(status = FileStatus.ERROR)
            }

            completed++
            emit(TransferProgress(files.size, completed, file.name, statuses.toList()))
        }
    }.flowOn(Dispatchers.IO)
}
