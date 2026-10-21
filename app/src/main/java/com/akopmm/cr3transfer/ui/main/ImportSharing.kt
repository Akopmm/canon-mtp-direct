package com.akopmm.cr3transfer.ui.main

import com.akopmm.cr3transfer.data.imports.ImportFolders.Companion.isRawImport
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.Context
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider

// Imported folders on disk and handing them to Lightroom / Immich.
/** Apps a set of imported RAW folders can be handed off to via the Android share sheet. */
internal enum class ShareTarget(
    val packageName: String,
    val pickerTitle: String,
    val confirmLabel: String,
    val missingAppMessage: String,
) {
    LIGHTROOM(
        packageName = "com.adobe.lrmobile",
        pickerTitle = "Select folders to import",
        confirmLabel = "Import",
        missingAppMessage = "No app available to import files",
    ),

    /**
     * Immich registers ACTION_SEND and ACTION_SEND_MULTIPLE for image MIME types, so shared
     * RAW files land in its upload queue. Immich reaches the home server over Tailscale itself —
     * this app still makes no network requests of its own.
     */
    IMMICH(
        packageName = "app.alextran.immich",
        pickerTitle = "Select folders to upload",
        confirmLabel = "Upload",
        missingAppMessage = "Immich is not installed",
    ),
}

internal fun launchCr3Share(context: Context, folders: List<java.io.File>, target: ShareTarget) {
    val rawFiles = folders.flatMap { folder ->
        folder.walkTopDown().filter { it.isRawImport() }.toList()
    }
    if (rawFiles.isEmpty()) {
        Toast.makeText(context, "No RAW files found in selected folders", Toast.LENGTH_SHORT).show()
        return
    }
    val authority = "${context.packageName}.fileprovider"
    val uris = ArrayList(rawFiles.map { FileProvider.getUriForFile(context, authority, it) })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        setPackage(target.packageName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val chooser = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(chooser, "Send ${rawFiles.size} RAW files"))
        } catch (_: Exception) {
            Toast.makeText(context, target.missingAppMessage, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
internal fun FolderPickerDialog(
    folders: List<java.io.File>,
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (List<java.io.File>) -> Unit,
) {
    // Default: pre-select the most recent folder (first after sortedDescending)
    var selected by remember {
        mutableStateOf(if (folders.isNotEmpty()) setOf(folders.first().absolutePath) else emptySet())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                items(folders) { folder ->
                    val cameraName = folder.parentFile?.name ?: "Unknown"
                    val displayLabel = "$cameraName / ${folder.name}"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (folder.absolutePath in selected) {
                                    selected - folder.absolutePath
                                } else {
                                    selected + folder.absolutePath
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = folder.absolutePath in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + folder.absolutePath else selected - folder.absolutePath
                            },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(displayLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // FIX: Check absolutePath instead of name
                    onConfirm(folders.filter { it.absolutePath in selected })
                },
                enabled = selected.isNotEmpty(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
