package com.akopmm.cr3transfer.data.imports

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.akopmm.cr3transfer.domain.imports.FolderPattern
import com.akopmm.cr3transfer.domain.imports.ImportLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.importLocationDataStore: DataStore<Preferences> by preferencesDataStore(name = "import_location")

/** Persists the chosen [ImportLocation] and every root used before it. */
@Singleton
class ImportLocationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val photoRootKey = stringPreferencesKey("photo_root")
    private val videoRootKey = stringPreferencesKey("video_root")
    private val patternKey = stringPreferencesKey("pattern")
    private val previousPhotoRootsKey = stringSetPreferencesKey("previous_photo_roots")
    private val previousVideoRootsKey = stringSetPreferencesKey("previous_video_roots")

    val location: Flow<ImportLocation> = context.importLocationDataStore.data.map { it.toLocation() }

    suspend fun current(): ImportLocation = location.first()

    /** Current, previous and default roots — the defaults are where every earlier version imported. */
    suspend fun knownRoots(): KnownRoots = withContext(Dispatchers.IO) {
        val prefs = context.importLocationDataStore.data.first()
        val current = prefs.toLocation()
        KnownRoots(
            photo = setOf(current.photoRoot, ImportLocation.DEFAULT_PHOTO_ROOT) + (prefs[previousPhotoRootsKey] ?: emptySet()),
            video = setOf(current.videoRoot, ImportLocation.DEFAULT_VIDEO_ROOT) + (prefs[previousVideoRootsKey] ?: emptySet()),
        )
    }

    suspend fun save(newLocation: ImportLocation) = withContext(Dispatchers.IO) {
        context.importLocationDataStore.edit { prefs ->
            val old = prefs.toLocation()
            prefs[previousPhotoRootsKey] = (prefs[previousPhotoRootsKey] ?: emptySet()) + old.photoRoot
            prefs[previousVideoRootsKey] = (prefs[previousVideoRootsKey] ?: emptySet()) + old.videoRoot
            prefs[photoRootKey] = newLocation.photoRoot
            prefs[videoRootKey] = newLocation.videoRoot
            prefs[patternKey] = newLocation.pattern.name
        }
    }

    private fun Preferences.toLocation() = ImportLocation(
        photoRoot = ImportLocation.normaliseRoot(this[photoRootKey]) ?: ImportLocation.DEFAULT_PHOTO_ROOT,
        videoRoot = ImportLocation.normaliseRoot(this[videoRootKey]) ?: ImportLocation.DEFAULT_VIDEO_ROOT,
        pattern = FolderPattern.entries.firstOrNull { it.name == this[patternKey] } ?: FolderPattern.CAMERA_IMPORT_DATE,
    )
}
