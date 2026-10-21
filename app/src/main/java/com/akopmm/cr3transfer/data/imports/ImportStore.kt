package com.akopmm.cr3transfer.data.imports

import android.content.Context
import android.os.Environment
import com.akopmm.cr3transfer.data.mtp.MtpDeviceManager
import com.akopmm.cr3transfer.domain.imports.ImportLocation
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

    suspend fun importedNames(): ImportedNamesCache = withContext(Dispatchers.IO) {
        folders.importedNames(locationRepository.knownRoots(), deviceManager.cameraDirectoryName)
    }

    suspend fun importFolders(): List<File> = withContext(Dispatchers.IO) {
        folders.importFolders(locationRepository.knownRoots().photo)
    }
}
