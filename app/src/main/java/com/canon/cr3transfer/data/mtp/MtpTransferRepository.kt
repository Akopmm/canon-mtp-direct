package com.canon.cr3transfer.data.mtp

import android.content.Context
import android.media.MediaScannerConnection
import android.mtp.MtpDevice
import android.os.Environment
import android.util.Log
import com.canon.cr3transfer.domain.model.CameraFile
import com.canon.cr3transfer.domain.model.FileStatus
import com.canon.cr3transfer.domain.model.FileTransferStatus
import com.canon.cr3transfer.domain.model.FileType
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

/**
 * Caches the set of already-imported filenames built from two walkTopDown passes.
 * Avoids the N×walkTopDown cost when dedup-checking many files at once.
 */
data class ImportedNamesCache(
    val photoNames: Set<String>,
    val videoNames: Set<String>,
) {
    fun contains(fileName: String, fileType: FileType): Boolean =
        if (fileType == FileType.MP4) fileName in videoNames else fileName in photoNames
}

@Singleton
class MtpTransferRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Single-file dedup check. Use [buildImportedNamesCache] when checking many files. */
    fun isAlreadyImported(fileName: String, fileType: FileType): Boolean {
        val root = if (fileType == FileType.MP4) videoOutputDirectory else photoOutputDirectory
        return root.walkTopDown().any { it.isFile && it.name == fileName }
    }

    /**
     * Builds filename sets for both photo and video roots in two walkTopDown passes.
     * Use this when dedup-checking many files at once to avoid N×walkTopDown.
     */
    fun buildImportedNamesCache(): ImportedNamesCache {
        val photos = photoOutputDirectory.walkTopDown()
            .filter { it.isFile }
            .mapTo(HashSet()) { it.name }
        val videos = videoOutputDirectory.walkTopDown()
            .filter { it.isFile }
            .mapTo(HashSet()) { it.name }
        return ImportedNamesCache(photos, videos)
    }

    fun transferFiles(
        device: MtpDevice,
        files: List<CameraFile>,
        deleteAfterTransfer: Boolean = false,
    ): Flow<TransferProgress> = flow {
        val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        Log.d(TAG, "Starting transfer to date folder: $dateFolder")

        // Build the dedup cache once instead of walking the filesystem per file
        val importedCache = buildImportedNamesCache()

        val statuses = files.map { file ->
            FileTransferStatus(
                fileName = file.name,
                sizeMb = file.sizeBytes / (1024.0 * 1024.0),
                status = FileStatus.PENDING,
            )
        }.toMutableList()

        var completed = 0

        for ((index, file) in files.withIndex()) {
            val destDir = when (file.fileType) {
                FileType.CR3 -> getPhotoDestDir(dateFolder)
                FileType.MP4 -> getVideoDestDir(dateFolder)
            }
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
                    Log.d(TAG, "Importing ${file.name} (handle=0x${file.objectHandle.toString(16)}) to ${destFile.absolutePath}")
                    val imported = device.importFile(file.objectHandle, destFile.absolutePath)
                    Log.d(TAG, "importFile returned: $imported, file exists: ${destFile.exists()}, size: ${destFile.length()}")
                    if (imported && destFile.exists() && destFile.length() > 0) {
                        val mimeType = when (file.fileType) {
                            FileType.CR3 -> "image/x-canon-cr3"
                            FileType.MP4 -> "video/mp4"
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

    private fun getPhotoDestDir(dateFolder: String): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "CanonImports/$dateFolder"
        )
        if (publicDir.mkdirs() || publicDir.isDirectory) {
            val testFile = File(publicDir, ".write_test")
            try {
                testFile.createNewFile()
                testFile.delete()
                return publicDir
            } catch (_: Exception) { }
        }
        val appDir = File(context.getExternalFilesDir(null), "CanonImports/photos/$dateFolder")
        appDir.mkdirs()
        return appDir
    }

    private fun getVideoDestDir(dateFolder: String): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "CanonImports/$dateFolder"
        )
        if (publicDir.mkdirs() || publicDir.isDirectory) {
            val testFile = File(publicDir, ".write_test")
            try {
                testFile.createNewFile()
                testFile.delete()
                return publicDir
            } catch (_: Exception) { }
        }
        val appDir = File(context.getExternalFilesDir(null), "CanonImports/videos/$dateFolder")
        appDir.mkdirs()
        return appDir
    }

    val photoOutputDirectory: File
        get() {
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "CanonImports"
            )
            return if (publicDir.isDirectory) publicDir
            else File(context.getExternalFilesDir(null), "CanonImports/photos")
        }

    val videoOutputDirectory: File
        get() {
            val publicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "CanonImports"
            )
            return if (publicDir.isDirectory) publicDir
            else File(context.getExternalFilesDir(null), "CanonImports/videos")
        }

    // Kept for backward compatibility — checks both dirs
    val outputDirectory: File get() = photoOutputDirectory
}
