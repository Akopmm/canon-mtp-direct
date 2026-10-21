package com.akopmm.cr3transfer.ui.main

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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import com.akopmm.cr3transfer.R
import com.akopmm.cr3transfer.data.export.ExportProgress
import com.akopmm.cr3transfer.data.export.ExportResult
import com.akopmm.cr3transfer.data.prefs.DefaultFolderInfo
import com.akopmm.cr3transfer.domain.model.CameraFile
import com.akopmm.cr3transfer.domain.model.FileType
import com.akopmm.cr3transfer.domain.model.TransferState
import com.akopmm.cr3transfer.ui.components.CameraSetupGuide
import com.akopmm.cr3transfer.ui.components.FileProgressItem
import com.akopmm.cr3transfer.ui.components.OverallProgressBar
import com.akopmm.cr3transfer.ui.components.TransferHistorySheet
import com.akopmm.cr3transfer.util.ThumbnailUtils

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
    val defaultFolder by viewModel.defaultFolder.collectAsState()
    val autoClearImports by viewModel.autoClearImports.collectAsState()
    val rememberCameraNicknames by viewModel.rememberCameraNicknames.collectAsState()
    val localExport by viewModel.localExport.collectAsState()
    val importsVersion by viewModel.importsVersion.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.localExportResults.collect { result ->
            Toast.makeText(context, localExportMessage(result), Toast.LENGTH_LONG).show()
        }
    }
    localExport?.let { LocalExportProgressDialog(it) }
    val settingsFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                android.util.Log.w("CR3Transfer", "Failed to take persistable URI permission: ${e.message}")
            }
            val displayName = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: "Local Folder"
            viewModel.saveDefaultFolder(uri.toString(), displayName)
            Toast.makeText(context, "Default local folder set to $displayName", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
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
                    defaultFolder = defaultFolder,
                    autoClearImports = autoClearImports,
                    onSaveDefaultFolder = { uri, name -> viewModel.saveDefaultFolder(uri, name) },
                    onClearDefaultFolder = { viewModel.clearDefaultFolder() },
                    importsVersion = importsVersion,
                    onExportToLocal = { folders, uri -> viewModel.exportToLocalFolder(folders, uri) },
                    onClearPreviousImports = {
                        viewModel.clearPreviousImports {
                            Toast.makeText(context, "Previous imports cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
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
                    onStartTransfer = {
                        if (viewModel.checkStorageAndProceed()) onStartTransfer()
                    },
                )
                is TransferState.Transferring -> TransferringContent(currentState)
                is TransferState.Done -> DoneContent(
                    state = currentState,
                    defaultFolder = defaultFolder,
                    autoClearImports = autoClearImports,
                    onSaveDefaultFolder = { uri, name -> viewModel.saveDefaultFolder(uri, name) },
                    onClearDefaultFolder = { viewModel.clearDefaultFolder() },
                    importsVersion = importsVersion,
                    onExportToLocal = { folders, uri -> viewModel.exportToLocalFolder(folders, uri) },
                    onClearPreviousImports = {
                        viewModel.clearPreviousImports {
                            Toast.makeText(context, "Previous imports cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
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

        if (showSettings) {
            SettingsDialog(
                defaultFolder = defaultFolder,
                autoClearImports = autoClearImports,
                rememberCameraNicknames = rememberCameraNicknames,
                onSetDefaultFolder = { settingsFolderPickerLauncher.launch(null) },
                onClearDefaultFolder = { viewModel.clearDefaultFolder() },
                onSetAutoClearImports = { viewModel.setAutoClearImports(it) },
                onSetRememberCameraNicknames = { viewModel.setRememberCameraNicknames(it) },
                onClearPreviousImports = {
                    viewModel.clearPreviousImports {
                        Toast.makeText(context, "Previous imports cleared", Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { showSettings = false },
            )
        }
    }
}

@Composable
private fun IdleContent(
    defaultFolder: DefaultFolderInfo?,
    autoClearImports: Boolean = false,
    onSaveDefaultFolder: (String, String) -> Unit,
    onClearDefaultFolder: () -> Unit,
    importsVersion: Int,
    onExportToLocal: (List<java.io.File>, Uri) -> Unit,
    onClearPreviousImports: () -> Unit,
) {
    var importFolders by remember { mutableStateOf<List<java.io.File>>(emptyList()) }

    LaunchedEffect(importsVersion) {
        importFolders = withContext(Dispatchers.IO) { findImportFolders() }
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
                ExportSection(
                    importFolders = importFolders,
                    defaultFolder = defaultFolder,
                    autoClearImports = autoClearImports,
                    onSaveDefaultFolder = onSaveDefaultFolder,
                    onClearDefaultFolder = onClearDefaultFolder,
                    onExportToLocal = onExportToLocal,
                    onClearPreviousImports = {
                        onClearPreviousImports()
                        importFolders = emptyList()
                    },
                )
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

/**
 * Ordering options for the picker grid. Camera enumeration order is folder-walk order, which is
 * neither of these; newest-first is the default because that is what you just shot.
 */
private enum class PickerSort(val label: String, val comparator: Comparator<CameraFile>) {
    NEWEST_FIRST(
        "Newest first",
        compareByDescending<CameraFile> { it.dateCreated }.thenByDescending { it.name },
    ),
    OLDEST_FIRST(
        "Oldest first",
        compareBy<CameraFile> { it.dateCreated }.thenBy { it.name },
    ),
    NAME_ASC(
        "Name A–Z",
        compareBy(String.CASE_INSENSITIVE_ORDER) { it.name },
    ),
    NAME_DESC(
        "Name Z–A",
        compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name },
    ),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePickerContent(
    state: TransferState.FilePicker,
    thumbnails: Map<Int, ByteArray>,
    ratings: Map<Int, Int>,
    ratingsComplete: Boolean,
    hdrHandles: Set<Int>,
    onToggleFile: (Int) -> Unit,
    onSelectAll: (Collection<Int>) -> Unit,
    onSelectNone: (List<Int>) -> Unit,
    onSelectOnly: (Collection<Int>, Collection<Int>) -> Unit,
    onToggleDeleteMode: () -> Unit,
    onStartTransfer: () -> Unit,
) {
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(PickerSort.NEWEST_FIRST) }
    var hiddenTypes by remember { mutableStateOf(emptySet<FileType>()) }
    var starredOnly by remember { mutableStateOf(false) }
    // Starred handles already selected for the user while the filter is on, so ratings that arrive
    // later are added once without re-selecting shots the user deliberately unticked.
    var autoSelectedStarred by remember { mutableStateOf(emptySet<Int>()) }
    LaunchedEffect(ratings, starredOnly) {
        if (!starredOnly) return@LaunchedEffect
        val newlyRated = starredHandles(state.files, ratings, hiddenTypes) - autoSelectedStarred
        if (newlyRated.isNotEmpty()) {
            autoSelectedStarred = autoSelectedStarred + newlyRated
            onSelectAll(newlyRated)
        }
    }
    var infoFile by remember { mutableStateOf<Pair<CameraFile, Int?>?>(null) }

    // Only offer filtering for the types this camera actually gave us.
    val availableTypes = remember(state.files) {
        state.files.map { it.fileType }.distinct().sortedBy { it.ordinal }
    }
    val visibleFiles = remember(state.files, sort, hiddenTypes, starredOnly, ratings) {
        state.files
            .filterNot { it.fileType in hiddenTypes }
            .filter { !starredOnly || (ratings[it.objectHandle] ?: 0) > 0 }
            .sortedWith(sort.comparator)
    }
    val visibleHandles = remember(visibleFiles) { visibleFiles.map { it.objectHandle } }

    val selectedCount = state.selectedHandles.size
    val visibleSelectedCount = visibleHandles.count { it in state.selectedHandles }
    // Files that are selected but filtered out still transfer — say so rather than surprise anyone.
    val hiddenSelectedCount = selectedCount - visibleSelectedCount

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

    infoFile?.let { (file, rating) ->
        FileInfoDialog(
            file = file,
            rating = rating,
            onDismiss = { infoFile = null },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Selection controls row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$visibleSelectedCount / ${visibleFiles.size} selected",
                style = MaterialTheme.typography.titleSmall,
            )
            Row {
                TextButton(onClick = { onSelectAll(visibleHandles) }) { Text("All") }
                TextButton(onClick = { onSelectNone(visibleHandles) }) { Text("None") }
            }
        }

        // Sort + type filter
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                var sortMenuOpen by remember { mutableStateOf(false) }
                TextButton(onClick = { sortMenuOpen = true }) { Text("Sort: ${sort.label}") }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    PickerSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                sort = option
                                sortMenuOpen = false
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            FilterChip(
                selected = starredOnly,
                onClick = {
                    starredOnly = !starredOnly
                    if (starredOnly) {
                        // "Starred" means "import my picks": select exactly the starred shots, so
                        // unstarred files hidden by the filter don't silently transfer as well.
                        val starred = starredHandles(state.files, ratings, hiddenTypes)
                        autoSelectedStarred = starred
                        if (starred.isEmpty() && ratingsComplete) {
                            Toast.makeText(context, "No starred files on the card", Toast.LENGTH_SHORT).show()
                        }
                        onSelectOnly(state.files.map { it.objectHandle }, starred)
                    }
                },
                label = { Text(if (ratingsComplete) "★ Starred" else "★ Starred…") },
                modifier = Modifier.padding(end = 4.dp),
            )
            if (availableTypes.size > 1) {
                availableTypes.forEach { type ->
                    FilterChip(
                        selected = type !in hiddenTypes,
                        onClick = {
                            hiddenTypes = if (type in hiddenTypes) hiddenTypes - type else hiddenTypes + type
                        },
                        label = { Text(type.name) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }

        if (hiddenSelectedCount > 0) {
            Text(
                text = "$hiddenSelectedCount selected ${if (hiddenSelectedCount == 1) "file is" else "files are"} hidden by the filter and will still transfer",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
            )
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
            items(visibleFiles, key = { it.objectHandle }) { file ->
                FileThumbnail(
                    file = file,
                    thumbnailData = thumbnails[file.objectHandle],
                    rating = ratings[file.objectHandle],
                    isHdr = file.objectHandle in hdrHandles,
                    isSelected = file.objectHandle in state.selectedHandles,
                    onClick = { onToggleFile(file.objectHandle) },
                    onShowInfo = { infoFile = Pair(file, ratings[file.objectHandle]) },
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
    rating: Int?,
    isHdr: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onShowInfo: () -> Unit,
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
        if (thumbnailData != null && thumbnailData.isNotEmpty()) {
            val bitmap = remember(thumbnailData) {
                try {
                    // Bytes are already trimmed to the JPEG in the ViewModel; extractJpeg is a
                    // cheap defensive no-op that also tolerates a leading prefix just in case.
                    val jpeg = ThumbnailUtils.extractJpeg(thumbnailData)
                    if (jpeg != null) {
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    // Decoding failed, return null to use placeholder
                    android.util.Log.d("CR3Transfer", "Thumbnail decode failed for ${file.name}: ${e.message}")
                    null
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PlaceholderThumbnail(file.fileType, isHdr)
            }
        } else {
            PlaceholderThumbnail(file.fileType, isHdr)
        }

        // Video play icon overlay
        if (file.fileType.isVideo) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Video",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.Center).size(32.dp),
            )
        }

        // Star rating badge overlay at top-left
        if (rating != null && rating > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Rating $rating stars",
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(12.dp),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "$rating",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }

        // Selection indicator at top-right
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

        // Info icon button at bottom-right
        IconButton(
            onClick = onShowInfo,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(2.dp)
                .size(24.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape),
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = "File Info",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
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
                .padding(end = 26.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(2.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlaceholderThumbnail(fileType: FileType, isHdr: Boolean = false) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (isHdr && fileType.isRaw) {
            // HDR-PQ RAW: no preview is decodable on Android (10-bit HEVC 4:2:2). Show a distinct
            // "HDR" tile so these shots are still identifiable in the grid.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "HDR",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = fileType.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                text = fileType.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun DoneContent(
    state: TransferState.Done,
    defaultFolder: DefaultFolderInfo?,
    autoClearImports: Boolean = false,
    onSaveDefaultFolder: (String, String) -> Unit,
    onClearDefaultFolder: () -> Unit,
    importsVersion: Int,
    onExportToLocal: (List<java.io.File>, Uri) -> Unit,
    onClearPreviousImports: () -> Unit,
) {
    var importFolders by remember { mutableStateOf<List<java.io.File>>(emptyList()) }

    LaunchedEffect(importsVersion) {
        importFolders = withContext(Dispatchers.IO) { findImportFolders() }
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
            ExportSection(
                importFolders = importFolders,
                defaultFolder = defaultFolder,
                autoClearImports = autoClearImports,
                onSaveDefaultFolder = onSaveDefaultFolder,
                onClearDefaultFolder = onClearDefaultFolder,
                onExportToLocal = onExportToLocal,
                onClearPreviousImports = {
                    onClearPreviousImports()
                    importFolders = emptyList()
                },
                showOpenFolderButton = true,
            )
        }
    }
}

@Composable
private fun ExportSection(
    importFolders: List<java.io.File>,
    defaultFolder: DefaultFolderInfo?,
    autoClearImports: Boolean = false,
    onSaveDefaultFolder: (String, String) -> Unit,
    onClearDefaultFolder: () -> Unit,
    onExportToLocal: (List<java.io.File>, Uri) -> Unit,
    onClearPreviousImports: () -> Unit,
    showOpenFolderButton: Boolean = false,
) {
    val context = LocalContext.current
    var pickerTarget by remember { mutableStateOf<ShareTarget?>(null) }
    var pendingLocalFolders by remember { mutableStateOf<List<java.io.File>?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Previous Imports?") },
            text = {
                Text(CLEAR_IMPORTS_WARNING)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onClearPreviousImports()
                    },
                ) { Text("Clear Imports", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            },
        )
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val folders = pendingLocalFolders
        pendingLocalFolders = null
        if (uri != null && folders != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                android.util.Log.w("CR3Transfer", "Failed to take persistable URI permission: ${e.message}")
            }
            val displayName = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: "Local Folder"
            onSaveDefaultFolder(uri.toString(), displayName)

            onExportToLocal(folders, uri)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
        Button(
            onClick = { pickerTarget = ShareTarget.LOCAL_FOLDER },
            enabled = importFolders.isNotEmpty(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Transfer to Local Folder")
                if (defaultFolder != null) {
                    Text(
                        text = "Default: ${defaultFolder.fullPath}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    )
                }
            }
        }
        if (showOpenFolderButton) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADCIM%2FCanonImports")
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "vnd.android.document/directory")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val fallback = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("content://com.android.externalstorage.documents/root/primary")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(fallback)
                    } catch (_: Exception) { }
                }
            }) {
                Text(stringResource(R.string.open_folder))
            }
        }
        if (importFolders.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = { showClearConfirm = true }) {
                Text("Clear Previous Imports", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    pickerTarget?.let { target ->
        val isLocalAndDefaultSet = target == ShareTarget.LOCAL_FOLDER && defaultFolder != null
        FolderPickerDialog(
            folders = importFolders,
            title = target.pickerTitle,
            confirmLabel = if (defaultFolder != null && target == ShareTarget.LOCAL_FOLDER) "Transfer to ${defaultFolder.fullPath}" else target.confirmLabel,
            secondaryButtonLabel = if (isLocalAndDefaultSet) "Choose different folder" else null,
            onSecondaryClick = if (isLocalAndDefaultSet) {
                { selected ->
                    pickerTarget = null
                    pendingLocalFolders = selected
                    folderPickerLauncher.launch(null)
                }
            } else null,
            onDismiss = { pickerTarget = null },
            onConfirm = { selected ->
                pickerTarget = null
                if (target == ShareTarget.LOCAL_FOLDER) {
                    if (defaultFolder != null) {
                        val uri = defaultFolder.uri
                        val doc = DocumentFile.fromTreeUri(context, uri)
                        if (doc != null && doc.canWrite()) {
                            onExportToLocal(selected, uri)
                        } else {
                            Toast.makeText(context, "Default folder inaccessible. Please select a new folder.", Toast.LENGTH_SHORT).show()
                            onClearDefaultFolder()
                            pendingLocalFolders = selected
                            folderPickerLauncher.launch(null)
                        }
                    } else {
                        pendingLocalFolders = selected
                        folderPickerLauncher.launch(null)
                    }
                } else {
                    launchCr3Share(context, selected, target)
                }
            },
        )
    }
}

@Composable
private fun FileInfoDialog(
    file: CameraFile,
    rating: Int?,
    onDismiss: () -> Unit,
) {
    val dateStr = remember(file.dateCreated) {
        if (file.dateCreated > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(file.dateCreated))
        } else "Unknown"
    }
    val sizeMbStr = remember(file.sizeBytes) {
        String.format(Locale.getDefault(), "%.2f MB (%d bytes)", file.sizeBytes / (1024.0 * 1024.0), file.sizeBytes)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Image File Information") },
        text = {
            Column {
                Text("Filename:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(file.name, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))

                Text("File Type:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "${file.fileType.name} (${when (file.fileType) {
                        FileType.CR3 -> "Canon RAW 3"
                        FileType.CR2 -> "Canon RAW 2"
                        FileType.JPG -> "JPEG Image"
                        FileType.HEIF -> "HEIF Image"
                        FileType.MP4 -> "MP4 Video"
                        FileType.MOV -> "QuickTime Video"
                    }})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text("File Size:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(sizeMbStr, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Text("Capture Date:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(dateStr, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Text("Camera Rating:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (rating != null && rating > 0) {
                        for (star in 1..5) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = if (star <= rating) Color(0xFFFFD700) else MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("$rating / 5 Stars", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("Unrated", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
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

/** RAW stills as written to disk: CR3 from Digic 8 and newer bodies, CR2 from the ones before. */
private val RAW_EXTENSIONS = listOf(".CR3", ".CR2")

private fun java.io.File.isRawImport(): Boolean =
    isFile && RAW_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

/** Returns date-named subdirectories of CanonImports that contain at least one RAW file, newest first. */
private fun findImportFolders(): List<java.io.File> {
    val importDir = java.io.File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        "CanonImports",
    )
    if (!importDir.exists()) return emptyList()

    return importDir.walkTopDown()
        .filter { dir ->
            dir.isDirectory && dir != importDir &&
                dir.listFiles()?.any { child -> child.isRawImport() } == true
        }
        .sortedWith(compareByDescending<java.io.File> { it.name }.thenByDescending { it.parentFile?.name })
        .toList()
}

/** Apps or locations a set of imported RAW folders can be handed off to. */
private enum class ShareTarget(
    val packageName: String?,
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

    LOCAL_FOLDER(
        packageName = null,
        pickerTitle = "Select folders to transfer",
        confirmLabel = "Choose Folder",
        missingAppMessage = "Could not open folder picker",
    ),
}

private fun launchCr3Share(context: Context, folders: List<java.io.File>, target: ShareTarget) {
    val targetPackage = target.packageName ?: return
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
        setPackage(targetPackage)
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
private fun SettingsDialog(
    defaultFolder: DefaultFolderInfo?,
    autoClearImports: Boolean,
    rememberCameraNicknames: Boolean,
    onSetDefaultFolder: () -> Unit,
    onClearDefaultFolder: () -> Unit,
    onSetAutoClearImports: (Boolean) -> Unit,
    onSetRememberCameraNicknames: (Boolean) -> Unit,
    onClearPreviousImports: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Previous Imports?") },
            text = {
                Text(CLEAR_IMPORTS_WARNING)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onDismiss()
                        onClearPreviousImports()
                    },
                ) { Text("Clear Imports", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Text(
                    text = "Default Local Folder",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (defaultFolder != null) defaultFolder.fullPath else "Not set (will prompt on transfer)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (defaultFolder != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (defaultFolder != null) {
                        TextButton(onClick = onClearDefaultFolder) {
                            Text("Clear Folder")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Button(onClick = {
                        onDismiss()
                        onSetDefaultFolder()
                    }) {
                        Text(if (defaultFolder != null) "Change Folder" else "Set Default Folder")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Clear import files after transfer",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Automatically delete local staging files (DCIM/CanonImports) once transfer to local folder is complete",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoClearImports,
                        onCheckedChange = onSetAutoClearImports,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Remember camera nicknames",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Save and reuse custom camera nicknames across connection sessions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = rememberCameraNicknames,
                        onCheckedChange = onSetRememberCameraNicknames,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Storage & Imports",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { showClearConfirm = true },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clear Previous Imports Now")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun FolderPickerDialog(
    folders: List<java.io.File>,
    title: String,
    confirmLabel: String,
    secondaryButtonLabel: String? = null,
    onSecondaryClick: ((List<java.io.File>) -> Unit)? = null,
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
            Row {
                if (secondaryButtonLabel != null && onSecondaryClick != null) {
                    TextButton(
                        onClick = {
                            onSecondaryClick(folders.filter { it.absolutePath in selected })
                        },
                        enabled = selected.isNotEmpty(),
                    ) { Text(secondaryButtonLabel) }
                }
                TextButton(
                    onClick = {
                        // FIX: Check absolutePath instead of name
                        onConfirm(folders.filter { it.absolutePath in selected })
                    },
                    enabled = selected.isNotEmpty(),
                ) { Text(confirmLabel) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private const val CLEAR_IMPORTS_WARNING =
    "This permanently deletes every imported photo and video on this phone (DCIM/CanonImports and " +
        "Movies/CanonImports) — copies already sent to Immich, Lightroom or another folder are not affected. " +
        "Transfer history is reset too, so the next time the camera is connected every file on the card " +
        "is offered as new. This cannot be undone."

private fun localExportMessage(result: ExportResult?): String {
    if (result == null) return "Cannot write to the selected folder"
    val n = result.verified
    val copied = "Transferred $n ${if (n == 1) "file" else "files"} to local folder"
    return when {
        result.failed.isNotEmpty() ->
            "$copied, ${result.failed.size} failed — nothing was deleted from folders with failures"
        result.clearedFolders.isNotEmpty() -> "$copied and cleared the import staging files"
        else -> copied
    }
}

@Composable
private fun LocalExportProgressDialog(progress: ExportProgress) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Copying to local folder") },
        text = {
            Column {
                if (progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.done.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${progress.done} / ${progress.total}" + (progress.currentFile?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {},
    )
}

/** Handles of starred (1–5 ★) files whose type isn't filtered out. */
private fun starredHandles(files: List<CameraFile>, ratings: Map<Int, Int>, hiddenTypes: Set<FileType>): Set<Int> =
    files.asSequence()
        .filter { it.fileType !in hiddenTypes && (ratings[it.objectHandle] ?: 0) > 0 }
        .map { it.objectHandle }
        .toSet()
