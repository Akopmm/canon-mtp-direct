package com.canon.cr3transfer.data.mtp

import android.content.Context
import android.media.MediaScannerConnection
import android.mtp.MtpDevice
import android.os.Environment
import android.util.Log
import com.canon.cr3transfer.data.prefs.AppSettings
import com.canon.cr3transfer.domain.model.CameraFile
import com.canon.cr3transfer.domain.model.FileStatus
import com.canon.cr3transfer.domain.model.FileTransferStatus
import com.canon.cr3transfer.domain.model.FileType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    val speedMbps: Double? = null,
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
    private val appSettings: AppSettings,
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
        val subfolder = appSettings.importSubfolder.first()
        val renameEnabled = appSettings.renameEnabled.first()
        val renameTemplate = appSettings.renameTemplate.first()
        Log.d(TAG, "Starting transfer to date folder: $dateFolder, subfolder=$subfolder, rename=$renameEnabled")

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
        var seqCounter = 1
        val recentSpeeds = ArrayDeque<Double>()

        for ((index, file) in files.withIndex()) {
            val destDir = when (file.fileType) {
                FileType.CR3 -> getPhotoDestDir(dateFolder, subfolder)
                FileType.MP4 -> getVideoDestDir(dateFolder, subfolder)
            }
            val resolvedName = if (renameEnabled) {
                resolveTemplate(renameTemplate, file, seqCounter, destDir)
            } else {
                file.name
            }
            val destFile = File(destDir, resolvedName)
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

            val fileStartMs = System.currentTimeMillis()
            val success = withContext(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Importing ${file.name} (handle=0x${file.objectHandle.toString(16)}) to ${destFile.absolutePath}")
                    val imported = device.importFile(file.objectHandle, destFile.absolutePath)
                    val destSize = destFile.length()
                    Log.d(TAG, "importFile returned: $imported, file exists: ${destFile.exists()}, size: $destSize, expected: ${file.sizeBytes}")
                    if (imported && destFile.exists() && destSize == file.sizeBytes) {
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
                        if (destFile.exists() && destSize != file.sizeBytes) {
                            Log.w(TAG, "Size mismatch for ${file.name}: expected ${file.sizeBytes}, got $destSize")
                        } else {
                            Log.e(TAG, "importFile failed for ${file.name}")
                        }
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
                seqCounter++
                statuses[index] = statuses[index].copy(status = FileStatus.DONE)
                val elapsedMs = System.currentTimeMillis() - fileStartMs
                if (elapsedMs > 0) {
                    val speed = file.sizeBytes / 1_048_576.0 / (elapsedMs / 1000.0)
                    recentSpeeds.addLast(speed)
                    if (recentSpeeds.size > 3) recentSpeeds.removeFirst()
                }
                Log.d(TAG, "Successfully transferred ${file.name} as ${destFile.name} (${destFile.length()} bytes)")
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
            val smoothedSpeed = if (recentSpeeds.isNotEmpty()) recentSpeeds.average() else null
            emit(TransferProgress(files.size, completed, file.name, statuses.toList(), smoothedSpeed))
        }
    }.flowOn(Dispatchers.IO)

    private fun getPhotoDestDir(dateFolder: String, subfolder: String = "CanonImports"): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "$subfolder/$dateFolder"
        )
        if (publicDir.mkdirs() || publicDir.isDirectory) {
            val testFile = File(publicDir, ".write_test")
            try {
                testFile.createNewFile()
                testFile.delete()
                return publicDir
            } catch (_: Exception) { }
        }
        val appDir = File(context.getExternalFilesDir(null), "$subfolder/photos/$dateFolder")
        appDir.mkdirs()
        return appDir
    }

    private fun getVideoDestDir(dateFolder: String, subfolder: String = "CanonImports"): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "$subfolder/$dateFolder"
        )
        if (publicDir.mkdirs() || publicDir.isDirectory) {
            val testFile = File(publicDir, ".write_test")
            try {
                testFile.createNewFile()
                testFile.delete()
                return publicDir
            } catch (_: Exception) { }
        }
        val appDir = File(context.getExternalFilesDir(null), "$subfolder/videos/$dateFolder")
        appDir.mkdirs()
        return appDir
    }

    private fun resolveTemplate(template: String, file: CameraFile, seq: Int, destDir: File): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(file.dateCreated))
        val original = file.name.substringBeforeLast(".")
        val ext = file.name.substringAfterLast(".", "")
        var name = template
            .replace("{date}", date)
            .replace("{seq}", seq.toString().padStart(3, '0'))
            .replace("{original}", original)
            .replace("{ext}", ext)
        // Handle collision: append _2, _3, ... if file already exists
        if (File(destDir, name).exists()) {
            val base = name.substringBeforeLast(".")
            val nameExt = name.substringAfterLast(".", "")
            var counter = 2
            while (File(destDir, if (nameExt.isEmpty()) "${base}_$counter" else "${base}_$counter.$nameExt").exists()) {
                counter++
            }
            name = if (nameExt.isEmpty()) "${base}_$counter" else "${base}_$counter.$nameExt"
        }
        return name
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
