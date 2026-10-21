package com.akopmm.cr3transfer.ui.main

import com.akopmm.cr3transfer.domain.imports.ImportLocation
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
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
import com.akopmm.cr3transfer.domain.model.TransferState

// Transfer summary and hand-off of the imported folders.
@Composable
internal fun DoneContent(
    state: TransferState.Done,
    importLocation: ImportLocation,
    loadImportFolders: suspend () -> List<java.io.File>,
) {
    val context = LocalContext.current
    var importFolders by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var pickerTarget by remember { mutableStateOf<ShareTarget?>(null) }

    LaunchedEffect(Unit) {
        importFolders = loadImportFolders()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.files_transferred, state.transferred, state.skipped),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (state.failed > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("${state.failed} files failed", color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { pickerTarget = ShareTarget.LIGHTROOM },
                enabled = importFolders.isNotEmpty(),
            ) {
                Text("Import to Lightroom")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { pickerTarget = ShareTarget.IMMICH },
                enabled = importFolders.isNotEmpty(),
            ) {
                Text("Send to Immich")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                val uri = android.net.Uri.parse(
                    "content://com.android.externalstorage.documents/document/primary%3A" +
                        android.net.Uri.encode(importLocation.photoRoot),
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "vnd.android.document/directory")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val fallback = Intent(Intent.ACTION_VIEW).apply {
                            data = android.net.Uri.parse("content://com.android.externalstorage.documents/root/primary")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(fallback)
                    } catch (_: Exception) { }
                }
            }) {
                Text(stringResource(R.string.open_folder))
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
