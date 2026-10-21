package com.akopmm.cr3transfer.ui.main

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.akopmm.cr3transfer.R
import com.akopmm.cr3transfer.domain.model.TransferState
import com.akopmm.cr3transfer.ui.components.CameraSetupGuide
import com.akopmm.cr3transfer.ui.components.FileProgressItem
import com.akopmm.cr3transfer.ui.components.OverallProgressBar
import com.akopmm.cr3transfer.ui.components.TransferHistorySheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onStartTransfer: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    val ratings by viewModel.ratings.collectAsState()
    val ratingsComplete by viewModel.ratingsComplete.collectAsState()
    val hdrHandles by viewModel.hdrHandles.collectAsState()
    val showHistory by viewModel.showHistory.collectAsState()
    val sessionHistory by viewModel.sessionHistory.collectAsState()
    val importLocation by viewModel.importLocation.collectAsState()
    var showLocationDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { viewModel.openHistory() }) {
                        Icon(Icons.Filled.List, contentDescription = "Transfer History")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val currentState = state) {
                is TransferState.Idle -> IdleContent(
                    importLocation = importLocation,
                    onEditImportLocation = { showLocationDialog = true },
                    loadImportFolders = viewModel::importFolders,
                )
                is TransferState.CameraConnected -> CameraConnectedContent(
                    cameraName = currentState.cameraName,
                    onSaveCameraName = { viewModel.saveCameraName(it) },
                )
                is TransferState.Scanning -> ScanningContent(currentState)
                is TransferState.FilePicker -> FilePickerContent(
                    state = currentState,
                    thumbnails = thumbnails,
                    ratings = ratings,
                    ratingsComplete = ratingsComplete,
                    hdrHandles = hdrHandles,
                    onToggleFile = { viewModel.toggleFileSelection(it) },
                    onSelectAll = { viewModel.selectHandles(it) },
                    onSelectNone = { viewModel.deselectHandles(it) },
                    onSelectOnly = { visible, target -> viewModel.selectOnly(visible, target) },
                    onToggleDeleteMode = { viewModel.toggleDeleteAfterTransfer() },
                    importLocation = importLocation,
                    onEditImportLocation = { showLocationDialog = true },
                    onStartTransfer = {
                        if (viewModel.checkStorageAndProceed()) onStartTransfer()
                    },
                )
                is TransferState.Transferring -> TransferringContent(currentState)
                is TransferState.Done -> DoneContent(
                    state = currentState,
                    importLocation = importLocation,
                    loadImportFolders = viewModel::importFolders,
                )
                is TransferState.Error -> ErrorContent(currentState)
            }
        }

        if (showLocationDialog) {
            ImportLocationDialog(
                current = importLocation,
                onSave = {
                    viewModel.setImportLocation(it)
                    showLocationDialog = false
                },
                onDismiss = { showLocationDialog = false },
            )
        }

        if (showHistory) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.closeHistory() },
                sheetState = rememberModalBottomSheetState(),
            ) {
                TransferHistorySheet(sessions = sessionHistory)
            }
        }
    }
}

@Composable
private fun CameraConnectedContent(
    cameraName: String?,
    onSaveCameraName: (String) -> Unit,
) {
    var enteredName by remember { mutableStateOf("") }
    val promptText = if (cameraName.isNullOrBlank()) {
        "Please give this camera a friendly name. This will be used for the import folder."
    } else {
        "Connected to $cameraName"
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            if (cameraName.isNullOrBlank()) {
                Text(promptText, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = enteredName,
                    onValueChange = { enteredName = it },
                    label = { Text("Camera name") },
                    placeholder = { Text("e.g. Studio R8") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { onSaveCameraName(enteredName) }) {
                    Text("Use this name")
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { onSaveCameraName("") }) {
                    Text("Use default camera id")
                }
            } else {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(promptText, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ScanningContent(state: TransferState.Scanning) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.scanning), style = MaterialTheme.typography.bodyLarge)
            if (state.discoveredCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${state.discoveredCount} files found\u2026",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TransferringContent(state: TransferState.Transferring) {
    Column(modifier = Modifier.fillMaxSize()) {
        OverallProgressBar(
            completed = state.completedFiles,
            total = state.totalFiles,
            currentFileName = state.currentFileName,
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.fileStatuses) { fileStatus ->
                FileProgressItem(fileStatus = fileStatus)
            }
        }
    }
}

@Composable
private fun ErrorContent(state: TransferState.Error) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        if (state.isCameraSetupError) {
            Spacer(modifier = Modifier.height(16.dp))
            CameraSetupGuide()
        }
    }
}
