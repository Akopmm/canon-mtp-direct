package com.akopmm.cr3transfer.data.prefs

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

data class DefaultFolderInfo(
    val uriString: String,
    val displayName: String,
) {
    val uri: Uri get() = Uri.parse(uriString)

    val fullPath: String
        get() {
            return try {
                val parsedUri = uri
                val path = parsedUri.path ?: return displayName
                val treeSegment = if (path.contains("/tree/")) {
                    path.substringAfter("/tree/").substringBefore("/document/")
                } else {
                    path
                }
                val decoded = Uri.decode(treeSegment)
                when {
                    decoded.startsWith("primary:") -> {
                        val subPath = decoded.substringAfter("primary:")
                        if (subPath.isBlank()) "/Internal Storage" else "/Internal Storage/$subPath"
                    }
                    decoded.contains(":") -> {
                        val volume = decoded.substringBefore(":")
                        val subPath = decoded.substringAfter(":")
                        if (subPath.isBlank()) "/SD Card ($volume)" else "/SD Card ($volume)/$subPath"
                    }
                    decoded.startsWith("/") -> decoded
                    else -> "/$decoded"
                }
            } catch (_: Exception) {
                displayName
            }
        }
}

@Singleton
class DefaultFolderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val defaultFolderUriKey = stringPreferencesKey("default_folder_uri")
    private val defaultFolderNameKey = stringPreferencesKey("default_folder_name")
    private val autoClearImportsKey = booleanPreferencesKey("auto_clear_imports")

    val defaultFolder: Flow<DefaultFolderInfo?> = context.settingsDataStore.data.map { prefs ->
        val uri = prefs[defaultFolderUriKey]
        val name = prefs[defaultFolderNameKey]
        if (!uri.isNullOrBlank() && !name.isNullOrBlank()) {
            DefaultFolderInfo(uriString = uri, displayName = name)
        } else null
    }

    val autoClearImports: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[autoClearImportsKey] ?: false
    }

    suspend fun saveDefaultFolder(uriString: String, displayName: String) = withContext(Dispatchers.IO) {
        context.settingsDataStore.edit { prefs ->
            prefs[defaultFolderUriKey] = uriString
            prefs[defaultFolderNameKey] = displayName
        }
    }

    suspend fun clearDefaultFolder() = withContext(Dispatchers.IO) {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(defaultFolderUriKey)
            prefs.remove(defaultFolderNameKey)
        }
    }

    suspend fun setAutoClearImports(enabled: Boolean) = withContext(Dispatchers.IO) {
        context.settingsDataStore.edit { prefs ->
            prefs[autoClearImportsKey] = enabled
        }
    }
}
