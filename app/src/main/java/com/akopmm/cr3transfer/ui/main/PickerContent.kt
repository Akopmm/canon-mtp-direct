package com.akopmm.cr3transfer.ui.main

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.graphics.BitmapFactory
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.akopmm.cr3transfer.domain.model.CameraFile
import com.akopmm.cr3transfer.domain.model.FileType
import com.akopmm.cr3transfer.domain.model.TransferState
import com.akopmm.cr3transfer.util.ThumbnailUtils

// File picker: thumbnails, ratings, sort/filter, selection and the file details dialog.
/**
 * Ordering options for the picker grid. Camera enumeration order is folder-walk order, which is
 * neither of these; newest-first is the default because that is what you just shot.
 */
internal enum class PickerSort(val label: String, val comparator: Comparator<CameraFile>) {
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
internal fun FilePickerContent(
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
internal fun FileThumbnail(
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
internal fun PlaceholderThumbnail(fileType: FileType, isHdr: Boolean = false) {
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
internal fun FileInfoDialog(
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

internal fun formatBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format("%.1f GB free on camera SD", gb)
    else String.format("%.0f MB free on camera SD", bytes / (1024.0 * 1024.0))
}

/** Handles of starred (1–5 ★) files whose type isn't filtered out. */
internal fun starredHandles(files: List<CameraFile>, ratings: Map<Int, Int>, hiddenTypes: Set<FileType>): Set<Int> =
    files.asSequence()
        .filter { it.fileType !in hiddenTypes && (ratings[it.objectHandle] ?: 0) > 0 }
        .map { it.objectHandle }
        .toSet()
