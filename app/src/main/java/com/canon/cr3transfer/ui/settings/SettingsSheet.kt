package com.canon.cr3transfer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.canon.cr3transfer.data.prefs.AppSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    appSettings: AppSettings,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val subfolder by appSettings.importSubfolder.collectAsState("CanonImports")
    val defaultSelection by appSettings.defaultSelection.collectAsState("NEW")
    val keepScreenOn by appSettings.keepScreenOn.collectAsState(false)
    val renameEnabled by appSettings.renameEnabled.collectAsState(false)
    val renameTemplate by appSettings.renameTemplate.collectAsState("{date}_{seq}.{ext}")
    val gridColumns by appSettings.gridColumns.collectAsState(3)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            HorizontalDivider()

            // Import subfolder
            Column {
                Text("Import subfolder", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Files saved to DCIM/<name>/<date>/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = subfolder,
                    onValueChange = { scope.launch { appSettings.setImportSubfolder(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            HorizontalDivider()

            // Default selection
            Column {
                Text("Default selection", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Applied when camera scan completes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                val selOptions = listOf("NEW" to "New", "ALL" to "All", "NONE" to "None")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    selOptions.forEachIndexed { i, (key, label) ->
                        SegmentedButton(
                            selected = defaultSelection == key,
                            onClick = { scope.launch { appSettings.setDefaultSelection(key) } },
                            shape = SegmentedButtonDefaults.itemShape(i, selOptions.size),
                        ) { Text(label) }
                    }
                }
            }

            HorizontalDivider()

            // Keep screen on
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keep screen on during transfer", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Prevents screen lock while transferring",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = keepScreenOn,
                    onCheckedChange = { scope.launch { appSettings.setKeepScreenOn(it) } },
                )
            }

            HorizontalDivider()

            // Rename files
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Rename files on import", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Use {date}, {seq}, {original}, {ext}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = renameEnabled,
                        onCheckedChange = { scope.launch { appSettings.setRenameEnabled(it) } },
                    )
                }
                if (renameEnabled) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = renameTemplate,
                        onValueChange = { scope.launch { appSettings.setRenameTemplate(it) } },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Template") },
                        placeholder = { Text("{date}_{seq}.{ext}") },
                    )
                }
            }

            HorizontalDivider()

            // Grid columns
            Column {
                Text("Grid columns", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                val colOptions = listOf(2, 3, 4)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    colOptions.forEachIndexed { i, cols ->
                        SegmentedButton(
                            selected = gridColumns == cols,
                            onClick = { scope.launch { appSettings.setGridColumns(cols) } },
                            shape = SegmentedButtonDefaults.itemShape(i, colOptions.size),
                        ) { Text("$cols") }
                    }
                }
            }
        }
    }
}
