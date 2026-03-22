package com.canon.cr3transfer.data.prefs

import android.content.Context
import com.canon.cr3transfer.domain.model.TransferSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferSessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file = File(context.filesDir, "transfer_sessions.json")

    suspend fun saveSession(session: TransferSession) = withContext(Dispatchers.IO) {
        val sessions = loadSessions().toMutableList()
        sessions.add(0, session)
        val array = JSONArray()
        sessions.take(100).forEach { s ->
            array.put(JSONObject().apply {
                put("id", s.id)
                put("dateMillis", s.dateMillis)
                put("transferred", s.transferred)
                put("skipped", s.skipped)
                put("failed", s.failed)
                put("totalBytes", s.totalBytes)
                put("durationMs", s.durationMs)
            })
        }
        file.writeText(array.toString())
    }

    suspend fun loadSessions(): List<TransferSession> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                TransferSession(
                    id = obj.getString("id"),
                    dateMillis = obj.getLong("dateMillis"),
                    transferred = obj.getInt("transferred"),
                    skipped = obj.getInt("skipped"),
                    failed = obj.getInt("failed"),
                    totalBytes = obj.getLong("totalBytes"),
                    durationMs = obj.getLong("durationMs"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
