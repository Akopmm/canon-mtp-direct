package com.akopmm.cr3transfer.data.imports

import android.content.Context
import android.os.Environment
import com.akopmm.cr3transfer.data.mtp.MtpDeviceManager
import com.akopmm.cr3transfer.domain.imports.DedupPolicy
import com.akopmm.cr3transfer.domain.imports.ImportLocation
import com.akopmm.cr3transfer.domain.imports.ImportedFiles
import com.akopmm.cr3transfer.domain.imports.LedgerEntry
import com.akopmm.cr3transfer.domain.model.CameraFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot used to tell new shots from ones imported before; see [DedupPolicy]. */
class ImportDedup(
    private val ledger: Map<String, List<LedgerEntry>>,
    private val onDisk: ImportedFiles,
) {
    /** Imported before (ledger or disk): such files start unselected in the picker. */
    fun wasImported(file: CameraFile): Boolean = DedupPolicy.wasImported(file, ledger, onDisk)

    /** An identical copy is in an import folder: the transfer skips it. A shot imported earlier but
     *  deleted locally is NOT skipped, so ticking it on purpose imports it again. */
    fun isOnDisk(file: CameraFile): Boolean = DedupPolicy.isOnDisk(file, onDisk)
}

/**
 * The one place that knows where imported files live: the transfer writes through it, dedup and the
 * home/done screens read through it. Everything else asks here instead of building paths.
 */
@Singleton
class ImportStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceManager: MtpDeviceManager,
    private val locationRepository: ImportLocationRepository,
) {
    val location: Flow<ImportLocation> = locationRepository.location

    private val folders: ImportFolders
        get() = ImportFolders(
            storageRoot = Environment.getExternalStorageDirectory(),
            fallbackRoot = context.getExternalFilesDir(null) ?: context.filesDir,
        )

    suspend fun setLocation(location: ImportLocation) = locationRepository.save(location)

    /** Directory for [file], created. MUST be called on Dispatchers.IO. */
    suspend fun destDirFor(file: CameraFile, importDay: LocalDate): File =
        folders.destDirFor(
            location = locationRepository.current(),
            file = file,
            camera = deviceManager.cameraDirectoryName,
            importDay = importDay,
            zone = ZoneId.systemDefault(),
        )

    private val ledger = ImportLedger(File(context.filesDir, "import_ledger"))

    /**
     * What the connected camera has already given us: its import ledger plus the files on disk. The
     * first time a camera is seen with this version, the ledger is seeded from the files on disk, so
     * everything imported before keeps counting even after those files are deleted.
     */
    suspend fun dedup(): ImportDedup = withContext(Dispatchers.IO) {
        val onDisk = folders.importedFiles(locationRepository.knownRoots(), deviceManager.cameraDirectoryName)
        val cameraId = deviceManager.cameraId
        val entries = if (cameraId == null) {
            emptyMap()
        } else {
            if (!ledger.exists(cameraId)) ledger.append(cameraId, onDisk.entries())
            ledger.load(cameraId)
        }
        ImportDedup(entries, onDisk)
    }

    /** Records a successful import in the connected camera's ledger. */
    suspend fun recordImported(file: CameraFile) = withContext(Dispatchers.IO) {
        deviceManager.cameraId?.let { ledger.append(it, listOf(LedgerEntry.of(file))) }
    }

    suspend fun importFolders(): List<File> = withContext(Dispatchers.IO) {
        folders.importFolders(locationRepository.knownRoots().photo)
    }
}
