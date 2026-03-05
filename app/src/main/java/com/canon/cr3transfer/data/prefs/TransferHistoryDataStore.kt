package com.canon.cr3transfer.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "transfer_history")

@Singleton
class TransferHistoryDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val transferredKey = stringSetPreferencesKey("transferred_files")

    val transferredFiles: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[transferredKey] ?: emptySet()
    }

    suspend fun markTransferred(fileName: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[transferredKey] ?: emptySet()
            prefs[transferredKey] = current + fileName
        }
    }

    suspend fun isTransferred(fileName: String): Boolean {
        return context.dataStore.data.first().let { prefs ->
            (prefs[transferredKey] ?: emptySet()).contains(fileName)
        }
    }
}
