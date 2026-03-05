package com.canon.cr3transfer.data.mtp

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.mtp.MtpDevice
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.canon.cr3transfer.data.prefs.TransferHistoryDataStore
import com.canon.cr3transfer.domain.model.Cr3File
import com.canon.cr3transfer.domain.model.FileStatus
import com.canon.cr3transfer.domain.model.FileTransferStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val history: TransferHistoryDataStore,
) {
    fun transferFiles(
        device: MtpDevice,
        files: List<Cr3File>,
    ): Flow<TransferProgress> = flow {
        val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val destDir = getDestDir(dateFolder)
        Log.d(TAG, "Destination directory: ${destDir.absolutePath}")

        val statuses = files.map { file ->
            FileTransferStatus(
                fileName = file.name,
                sizeMb = file.sizeBytes / (1024.0 * 1024.0),
                status = FileStatus.PENDING,
            )
        }.toMutableList()

        var completed = 0

        for ((index, file) in files.withIndex()) {
            val destFile = File(destDir, file.name)
            val alreadyTransferred = destFile.exists() && destFile.length() == file.sizeBytes
            Log.d(TAG, "File ${file.name}: exists=${destFile.exists()} expectedSize=${file.sizeBytes} actualSize=${if (destFile.exists()) destFile.length() else 0} skip=$alreadyTransferred")

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
                    Log.d(TAG, "Importing ${file.name} (handle=0x${file.objectHandle.toString(16)}) to ${destFile.absolutePath}")
                    val imported = device.importFile(file.objectHandle, destFile.absolutePath)
                    Log.d(TAG, "importFile returned: $imported, file exists: ${destFile.exists()}, size: ${destFile.length()}")
                    if (imported && destFile.exists() && destFile.length() > 0) {
                        // Trigger media scan so it shows in gallery/file managers
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(destFile.absolutePath),
                            arrayOf("application/octet-stream"),
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
            } else {
                statuses[index] = statuses[index].copy(status = FileStatus.ERROR)
            }

            completed++
            emit(TransferProgress(files.size, completed, file.name, statuses.toList()))
        }
    }.flowOn(Dispatchers.IO)

    private fun getDestDir(dateFolder: String): File {
        // Try public DCIM first (requires MANAGE_EXTERNAL_STORAGE on Android 11+)
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "CanonImports/$dateFolder"
        )
        if (publicDir.mkdirs() || publicDir.isDirectory) {
            // Test if we can actually write here
            val testFile = File(publicDir, ".write_test")
            try {
                testFile.createNewFile()
                testFile.delete()
                return publicDir
            } catch (_: Exception) {
                // Can't write, fall through
            }
        }

        // Fallback to app-specific external storage (always writable, visible in file manager)
        val appDir = File(
            context.getExternalFilesDir(null),
            "CanonImports/$dateFolder"
        )
        appDir.mkdirs()
        Log.d(TAG, "Using app-specific storage: ${appDir.absolutePath}")
        return appDir
    }

    val outputDirectory: File
        get() {
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "CanonImports"
            )
            return if (publicDir.isDirectory) publicDir
            else File(context.getExternalFilesDir(null), "CanonImports")
        }
}
