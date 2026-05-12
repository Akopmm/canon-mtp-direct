package com.canon.cr3transfer.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.cameraNamesDataStore: DataStore<Preferences> by preferencesDataStore(name = "camera_names")

@Singleton
class CameraNameRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private fun keyFor(cameraId: String) = stringPreferencesKey("camera_name_${cameraId}")

    suspend fun getCameraName(cameraId: String): String? = withContext(Dispatchers.IO) {
        context.cameraNamesDataStore.data
            .map { prefs -> prefs[keyFor(cameraId)] }
            .first()
    }

    suspend fun saveCameraName(cameraId: String, cameraName: String) = withContext(Dispatchers.IO) {
        context.cameraNamesDataStore.edit { prefs ->
            prefs[keyFor(cameraId)] = cameraName.trim()
        }
    }
}
