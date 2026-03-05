package com.canon.cr3transfer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.canon.cr3transfer.domain.model.FileStatus
import com.canon.cr3transfer.domain.model.FileTransferStatus
import java.util.Locale

@Composable
fun FileProgressItem(
    fileStatus: FileTransferStatus,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fileStatus.fileName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = String.format(Locale.US, "%.1f MB", fileStatus.sizeMb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Text(
            text = when (fileStatus.status) {
                FileStatus.PENDING -> "\u23F3"
                FileStatus.TRANSFERRING -> "\u25B6"
                FileStatus.DONE -> "\u2705"
                FileStatus.SKIPPED -> "\u23ED"
                FileStatus.ERROR -> "\u274C"
            },
        )
    }
}
