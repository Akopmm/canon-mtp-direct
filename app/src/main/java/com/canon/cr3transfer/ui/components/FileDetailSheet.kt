package com.canon.cr3transfer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.canon.cr3transfer.data.exif.ExifReader
import com.canon.cr3transfer.domain.model.CameraFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileDetailSheet(
    file: CameraFile,
    exifData: ExifReader.ExifData?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(file.name, style = MaterialTheme.typography.titleMedium)

            HorizontalDivider()

            DetailRow("Size", formatBytes(file.sizeBytes))
            DetailRow(
                "Captured",
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(file.dateCreated)),
            )

            if (exifData != null) {
                HorizontalDivider()
                DetailRow("ISO", exifData.iso?.toString() ?: "–")
                DetailRow("Aperture", exifData.aperture ?: "–")
                DetailRow("Shutter", exifData.shutterSpeed ?: "–")
            } else {
                Text(
                    "EXIF available after transfer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format("%.1f GB", gb)
    else String.format("%.0f MB", bytes / (1024.0 * 1024.0))
}
