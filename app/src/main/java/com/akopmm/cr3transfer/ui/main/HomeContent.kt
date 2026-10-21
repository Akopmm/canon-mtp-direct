package com.akopmm.cr3transfer.ui.main

import com.akopmm.cr3transfer.domain.imports.ImportLocation
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.akopmm.cr3transfer.R

// Home screen (no camera connected): connect prompt plus hand-off of earlier imports.
@Composable
internal fun IdleContent(
    importLocation: ImportLocation,
    onEditImportLocation: () -> Unit,
    loadImportFolders: suspend () -> List<java.io.File>,
) {
    val context = LocalContext.current
    var importFolders by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var pickerTarget by remember { mutableStateOf<ShareTarget?>(null) }

    LaunchedEffect(importLocation) {
        importFolders = loadImportFolders()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Text("Canon CR3 Transfer", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.connect_prompt),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            ImportLocationRow(location = importLocation, onEdit = onEditImportLocation)
            if (importFolders.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Previously transferred files available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { pickerTarget = ShareTarget.LIGHTROOM }) {
                    Text("Import to Lightroom")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { pickerTarget = ShareTarget.IMMICH }) {
                    Text("Send to Immich")
                }
            }
        }
    }

    pickerTarget?.let { target ->
        FolderPickerDialog(
            folders = importFolders,
            title = target.pickerTitle,
            confirmLabel = target.confirmLabel,
            onDismiss = { pickerTarget = null },
            onConfirm = { selected ->
                pickerTarget = null
                launchCr3Share(context, selected, target)
            },
        )
    }
}
