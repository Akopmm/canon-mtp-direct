package com.canon.cr3transfer.ui.main

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.canon.cr3transfer.R
import com.canon.cr3transfer.domain.model.CameraFile
import com.canon.cr3transfer.domain.model.FileType
import com.canon.cr3transfer.domain.model.TransferState
import com.canon.cr3transfer.ui.components.CameraSetupGuide
import com.canon.cr3transfer.ui.components.FileProgressItem
import com.canon.cr3transfer.ui.components.OverallProgressBar
import com.canon.cr3transfer.ui.components.TransferHistorySheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onStartTransfer: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    val showHistory by viewModel.showHistory.collectAsState()
    val sessionHistory by viewModel.sessionHistory.collectAsState()

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
                is TransferState.Idle -> IdleContent()
                is TransferState.CameraConnected -> CameraConnectedContent()
                is TransferState.Scanning -> ScanningContent()
                is TransferState.FilePicker -> FilePickerContent(
                    state = currentState,
                    thumbnails = thumbnails,
                    onToggleFile = { viewModel.toggleFileSelection(it) },
                    onSelectAll = { viewModel.selectAll() },
                    onSelectNone = { viewModel.selectNone() },
                    onToggleDeleteMode = { viewModel.toggleDeleteAfterTransfer() },
                    onStartTransfer = {
                        if (viewModel.checkStorageAndProceed()) onStartTransfer()
                    },
                )
                is TransferState.Transferring -> TransferringContent(currentState)
                is TransferState.Done -> DoneContent(currentState)
                is TransferState.Error -> ErrorContent(currentState)
            }
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
private fun IdleContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Canon CR3 Transfer", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.connect_prompt),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CameraConnectedContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Camera Connected", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ScanningContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.scanning), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePickerContent(
    state: TransferState.FilePicker,
    thumbnails: Map<Int, ByteArray>,
    onToggleFile: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onToggleDeleteMode: () -> Unit,
    onStartTransfer: () -> Unit,
) {
    val selectedCount = state.selectedHandles.size
    val totalCount = state.files.size
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete files from camera?") },
            text = {
                Text("After each file is successfully transferred, it will be permanently deleted from the camera's SD card. This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onStartTransfer()
                }) { Text("Delete & Transfer") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Selection controls row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$selectedCount / $totalCount selected", style = MaterialTheme.typography.titleSmall)
            Row {
                TextButton(onClick = onSelectAll) { Text("All") }
                TextButton(onClick = onSelectNone) { Text("None") }
            }
        }

        // SD card free space
        state.cameraFreeBytes?.let { freeBytes ->
            Text(
                text = formatBytes(freeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
            )
        }

        HorizontalDivider()

        // Delete toggle
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Delete from camera after transfer", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = state.deleteAfterTransfer, onCheckedChange = { onToggleDeleteMode() })
        }

        HorizontalDivider()

        // Thumbnail grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.files, key = { it.objectHandle }) { file ->
                FileThumbnail(
                    file = file,
                    thumbnailData = thumbnails[file.objectHandle],
                    isSelected = file.objectHandle in state.selectedHandles,
                    onClick = { onToggleFile(file.objectHandle) },
                )
            }
        }

        // Transfer button
        Button(
            onClick = {
                if (state.deleteAfterTransfer) showDeleteConfirm = true
                else onStartTransfer()
            },
            enabled = selectedCount > 0,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text("Transfer $selectedCount ${if (selectedCount == 1) "file" else "files"}")
        }
    }
}

@Composable
private fun FileThumbnail(
    file: CameraFile,
    thumbnailData: ByteArray?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val borderWidth = if (isSelected) 3.dp else 0.dp

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        if (thumbnailData != null) {
            val bitmap = remember(thumbnailData) {
                BitmapFactory.decodeByteArray(thumbnailData, 0, thumbnailData.size)
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PlaceholderThumbnail(file.fileType)
            }
        } else {
            PlaceholderThumbnail(file.fileType)
        }

        // Video play icon overlay
        if (file.fileType == FileType.MP4) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Video",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center).size(32.dp),
            )
        }

        // Selection indicator
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .background(Color.White, CircleShape),
            )
        }

        // Filename at bottom
        Text(
            text = file.name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(2.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlaceholderThumbnail(fileType: FileType) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (fileType == FileType.MP4) "MP4" else "CR3",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun DoneContent(state: TransferState.Done) {
    val context = LocalContext.current
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
            Button(onClick = {
                val importDir = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "CanonImports"
                )
                val cr3Files = importDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".CR3", ignoreCase = true) }
                    .toList()

                if (cr3Files.isEmpty()) {
                    Toast.makeText(context, "No CR3 files found", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                val authority = "${context.packageName}.fileprovider"
                val uris = ArrayList(cr3Files.map { file ->
                    FileProvider.getUriForFile(context, authority, file)
                })

                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    setPackage("com.adobe.lrmobile")
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
                        context.startActivity(Intent.createChooser(chooser, "Import ${cr3Files.size} CR3 files"))
                    } catch (_: Exception) {
                        Toast.makeText(context, "No app available to import files", Toast.LENGTH_SHORT).show()
                    }
                }
            }) {
                Text("Import to Lightroom")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                val uri = android.net.Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADCIM%2FCanonImports")
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

private fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format("%.1f GB free on camera SD", gb)
    else String.format("%.0f MB free on camera SD", bytes / (1024.0 * 1024.0))
}
