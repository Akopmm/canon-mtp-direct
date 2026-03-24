package com.canon.cr3transfer.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

@Singleton
class AppSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.appSettingsDataStore

    companion object {
        val IMPORT_SUBFOLDER = stringPreferencesKey("import_subfolder")
        val DEFAULT_SELECTION = stringPreferencesKey("default_selection") // "NEW" | "ALL" | "NONE"
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val RENAME_ENABLED = booleanPreferencesKey("rename_enabled")
        val RENAME_TEMPLATE = stringPreferencesKey("rename_template")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
    }

    val importSubfolder: Flow<String> = store.data.map { it[IMPORT_SUBFOLDER] ?: "CanonImports" }
    val defaultSelection: Flow<String> = store.data.map { it[DEFAULT_SELECTION] ?: "NEW" }
    val keepScreenOn: Flow<Boolean> = store.data.map { it[KEEP_SCREEN_ON] ?: false }
    val renameEnabled: Flow<Boolean> = store.data.map { it[RENAME_ENABLED] ?: false }
    val renameTemplate: Flow<String> = store.data.map { it[RENAME_TEMPLATE] ?: "{date}_{seq}.{ext}" }
    val gridColumns: Flow<Int> = store.data.map { it[GRID_COLUMNS] ?: 3 }

    suspend fun setImportSubfolder(value: String) = store.edit { it[IMPORT_SUBFOLDER] = value }
    suspend fun setDefaultSelection(value: String) = store.edit { it[DEFAULT_SELECTION] = value }
    suspend fun setKeepScreenOn(value: Boolean) = store.edit { it[KEEP_SCREEN_ON] = value }
    suspend fun setRenameEnabled(value: Boolean) = store.edit { it[RENAME_ENABLED] = value }
    suspend fun setRenameTemplate(value: String) = store.edit { it[RENAME_TEMPLATE] = value }
    suspend fun setGridColumns(value: Int) = store.edit { it[GRID_COLUMNS] = value }
}
