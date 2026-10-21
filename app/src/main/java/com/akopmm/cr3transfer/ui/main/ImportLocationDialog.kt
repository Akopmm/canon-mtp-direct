package com.akopmm.cr3transfer.ui.main

import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akopmm.cr3transfer.domain.imports.FolderPattern
import com.akopmm.cr3transfer.domain.imports.ImportLocation

// "Saving to …" row and the dialog that changes where imports go.

/** One tappable line: where the next import will be written. */
@Composable
internal fun ImportLocationRow(location: ImportLocation, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Saving to ${location.photoRoot}/…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = "Change where imports go",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
internal fun ImportLocationDialog(
    current: ImportLocation,
    onSave: (ImportLocation) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var pattern by remember { mutableStateOf(current.pattern) }
    // The folder choice: one of the presets, or a custom root (photos and videos together).
    var customRoot by remember {
        mutableStateOf(current.photoRoot.takeIf { current.photoRoot == current.videoRoot })
    }
    var preset by remember {
        mutableStateOf(
            when {
                customRoot != null -> null
                current.photoRoot == ImportLocation.PICTURES.photoRoot -> ImportLocation.PICTURES
                else -> ImportLocation.DEFAULT
            },
        )
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val root = ImportLocation.rootFromTreeDocumentId(DocumentsContract.getTreeDocumentId(uri))
        if (root == null) {
            Toast.makeText(
                context,
                "Pick a folder on the phone's internal storage (not an SD card, the storage root or Android/)",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            customRoot = root
            preset = null
        }
    }

    val chosen = preset?.copy(pattern = pattern)
        ?: customRoot?.let { ImportLocation.custom(it, pattern) }
        ?: ImportLocation.DEFAULT.copy(pattern = pattern)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where imports go") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Folder", style = MaterialTheme.typography.titleSmall)
                for (option in listOf(ImportLocation.DEFAULT, ImportLocation.PICTURES)) {
                    ChoiceRow(
                        selected = preset == option,
                        label = option.description,
                        onClick = { preset = option },
                    )
                }
                ChoiceRow(
                    selected = preset == null && customRoot != null,
                    label = customRoot?.let { "$it (photos and videos)" } ?: "Choose a folder…",
                    onClick = { if (customRoot == null) folderPicker.launch(null) else preset = null },
                )
                if (customRoot != null) {
                    TextButton(onClick = { folderPicker.launch(null) }) { Text("Choose a different folder…") }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Organise by", style = MaterialTheme.typography.titleSmall)
                for (option in FolderPattern.entries) {
                    ChoiceRow(selected = pattern == option, label = option.label, onClick = { pattern = option })
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Files already imported stay where they are and are still recognised as imported. " +
                        "Apps that watch the old folder (a sync app, for example) need pointing at the new one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(chosen) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
