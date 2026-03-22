package com.canon.cr3transfer.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.canon.cr3transfer.domain.model.TransferSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TransferHistorySheet(sessions: List<TransferSession>) {
    if (sessions.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("No transfer history yet.")
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        item {
            Text(
                text = "Transfer History",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        items(sessions, key = { it.id }) { session ->
            TransferSessionItem(session = session)
            HorizontalDivider()
        }
    }
}

@Composable
private fun TransferSessionItem(session: TransferSession) {
    val dateStr = remember(session.dateMillis) {
        SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(Date(session.dateMillis))
    }
    val durationStr = remember(session.durationMs) {
        val secs = session.durationMs / 1000
        if (secs < 60) "${secs}s" else "${secs / 60}m ${secs % 60}s"
    }
    val sizeStr = remember(session.totalBytes) {
        val mb = session.totalBytes / (1024.0 * 1024.0)
        if (mb >= 1024) String.format("%.1f GB", mb / 1024) else String.format("%.0f MB", mb)
    }
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = dateStr, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "${session.transferred} transferred · ${session.skipped} skipped" +
                if (session.failed > 0) " · ${session.failed} failed" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "$sizeStr in $durationStr",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
