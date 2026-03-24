# Canon CR3 Transfer — v1.1 Features Plan

## Features

1. **Video support (MP4)** — enumerate and transfer `.MP4` files alongside `.CR3`
2. **Completion notification** — summary notification after transfer finishes
3. **Camera SD card free space** — show remaining space on camera before transfer
4. **Transfer history screen** — log of past sessions in a bottom sheet
5. **Optional delete from camera after transfer** — per-session toggle with confirmation

---

## Implementation Order

Features must be implemented in this order due to dependencies:

1. Feature 1 (Video/MP4) — renames `Cr3File` → `CameraFile`, adds `FileType`; foundation for all others
2. Feature 3 (SD card space) — additive field on `FilePicker`, no breaking changes
3. Feature 5 (Delete toggle) — additive field on `FilePicker`, flag through use case/repo
4. Feature 2 (Completion notification) — self-contained in `TransferForegroundService`
5. Feature 4 (History) — new files + inject into service/viewmodel + bottom sheet

---

## Feature 1: Video Support (MP4)

### Goal
Enumerate `.MP4` files alongside `.CR3`. Save photos to `DCIM/CanonImports/YYYY-MM-DD/`, videos to `Movies/CanonImports/YYYY-MM-DD/`. Show a video play icon overlay in the picker grid.

### Files to Modify

#### `domain/model/Cr3File.kt`
- Add `enum class FileType { CR3, MP4 }`
- Rename `Cr3File` → `CameraFile`, add `fileType: FileType` field
- All usages throughout the codebase updated accordingly

```kotlin
enum class FileType { CR3, MP4 }

data class CameraFile(
    val objectHandle: Int,
    val name: String,
    val sizeBytes: Long,
    val dateCreated: Long,
    val fileType: FileType,
)
```

#### `data/mtp/MtpFileEnumerator.kt`
- Rename `enumerateCr3Files` → `enumerateCameraFiles`, return `List<CameraFile>`
- Match both `.CR3` and `.MP4` extensions (case-insensitive) when collecting files:

```kotlin
info.name.endsWith(".CR3", ignoreCase = true) -> results.add(CameraFile(..., fileType = FileType.CR3))
info.name.endsWith(".MP4", ignoreCase = true) -> results.add(CameraFile(..., fileType = FileType.MP4))
```

#### `data/mtp/MtpTransferRepository.kt`
- Split `getDestDir()` into `getPhotoDestDir(dateFolder)` (`DCIM/CanonImports/`) and `getVideoDestDir(dateFolder)` (`Movies/CanonImports/`)
- Dispatch destination based on `file.fileType` in the transfer loop
- Dispatch MIME type for `MediaScannerConnection.scanFile`:
  - CR3 → `"image/x-canon-cr3"`
  - MP4 → `"video/mp4"`
- Update `isAlreadyImported(fileName, fileType)` to walk the correct root directory

#### `domain/usecase/ScanCameraUseCase.kt`
- Return type `List<Cr3File>` → `List<CameraFile>`
- Call `fileEnumerator.enumerateCameraFiles(device, storageId)`

#### `domain/usecase/TransferFilesUseCase.kt`
- Parameter type `List<Cr3File>` → `List<CameraFile>`

#### `domain/model/TransferState.kt`
- `FilePicker.files` type: `List<Cr3File>` → `List<CameraFile>`

#### `service/TransferForegroundService.kt`
- `startTransfer(files: List<Cr3File>)` → `startTransfer(files: List<CameraFile>, ...)`

#### `ui/main/MainViewModel.kt`
- `scannedFiles: List<Cr3File>` → `List<CameraFile>` (no logic changes, only types)

#### `ui/main/MainScreen.kt`
- In the thumbnail composable, add a `PlayCircleOutline` icon overlay for MP4 files:

```kotlin
if (file.fileType == FileType.MP4) {
    Icon(
        imageVector = Icons.Filled.PlayCircleOutline,
        contentDescription = "Video",
        tint = Color.White,
        modifier = Modifier.align(Alignment.Center).size(32.dp)
    )
}
```
- Update placeholder to show `"MP4"` label for video files

---

## Feature 2: Completion Notification

### Goal
After transfer completes, post a dismissible summary notification ("X transferred, Y skipped, Z failed"). Separate from the in-progress foreground notification.

### Files to Modify

#### `service/TransferForegroundService.kt`
- Add second notification channel `"transfer_complete_channel"` with `IMPORTANCE_DEFAULT`
- Add `COMPLETION_NOTIFICATION_ID = 2`
- Add `postCompletionNotification(transferred, skipped, failed)`:

```kotlin
private fun postCompletionNotification(transferred: Int, skipped: Int, failed: Int) {
    val notification = NotificationCompat.Builder(this, COMPLETION_CHANNEL_ID)
        .setContentTitle("Canon Transfer Complete")
        .setContentText("$transferred transferred, $skipped skipped, $failed failed")
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setAutoCancel(true)
        .setOngoing(false)
        .build()
    getSystemService(NotificationManager::class.java).notify(COMPLETION_NOTIFICATION_ID, notification)
}
```

- When transfer finishes: `stopForeground(STOP_FOREGROUND_REMOVE)` → `postCompletionNotification(...)` → `stopSelf()`

> `POST_NOTIFICATIONS` permission already declared in manifest. No manifest changes needed.

---

## Feature 3: Camera SD Card Free Space

### Goal
Fetch and display the camera's remaining SD card space in the picker UI before the user starts a transfer.

### Files to Modify

#### `data/mtp/MtpDeviceManager.kt`
- Add method (call on `Dispatchers.IO`):

```kotlin
fun getCameraFreeBytes(): Long? {
    val ids = mtpDevice?.storageIds ?: return null
    if (ids.isEmpty()) return null
    val info = mtpDevice?.getStorageInfo(ids[0]) ?: return null
    return if (info.freeSpace >= 0) info.freeSpace else null
}
```

(`MtpDevice.getStorageInfo` available from API 24+; minSdk is 26 — safe.)

#### `domain/model/TransferState.kt`
- Add `cameraFreeBytes: Long? = null` to `FilePicker` (default `null` = not yet fetched)

#### `ui/main/MainViewModel.kt`
- After setting `FilePicker` state in `scanCamera()`, launch a coroutine to fetch and update:

```kotlin
viewModelScope.launch(Dispatchers.IO) {
    val freeBytes = deviceManager.getCameraFreeBytes()
    val current = _state.value
    if (current is TransferState.FilePicker) {
        _state.value = current.copy(cameraFreeBytes = freeBytes)
    }
}
```

#### `ui/main/MainScreen.kt`
- In `FilePickerContent`, below the selection controls row:

```kotlin
state.cameraFreeBytes?.let { freeBytes ->
    Text(
        text = formatBytes(freeBytes), // e.g. "12.4 GB free on camera SD"
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
```

- Add top-level `formatBytes(bytes: Long): String` helper.

---

## Feature 4: Transfer History Screen

### Goal
Persist completed transfer sessions to a JSON file. Show them in a `ModalBottomSheet` triggered by a history icon in the top bar.

### New Files to Create

#### `domain/model/TransferSession.kt`
```kotlin
data class TransferSession(
    val id: String,        // timestamp string for list keying
    val dateMillis: Long,
    val transferred: Int,
    val skipped: Int,
    val failed: Int,
    val totalBytes: Long,
    val durationMs: Long,
)
```

#### `data/prefs/TransferSessionRepository.kt`
- Reads/writes `files-dir/transfer_sessions.json` using `org.json` (built into Android, no extra dep)
- Stores newest-first, capped at 100 sessions

```kotlin
@Singleton
class TransferSessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file = File(context.filesDir, "transfer_sessions.json")

    suspend fun saveSession(session: TransferSession) = withContext(Dispatchers.IO) {
        val sessions = loadSessions().toMutableList()
        sessions.add(0, session)
        writeToFile(sessions.take(100))
    }

    suspend fun loadSessions(): List<TransferSession> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList() else parseJson(file.readText())
    }
}
```

#### `ui/components/TransferHistorySheet.kt`
- `TransferHistorySheet(sessions: List<TransferSession>)` composable
- Empty state: "No transfer history yet."
- List: each session shows date, counts ("47 transferred, 0 skipped, 0 failed"), total size and duration ("1.2 GB in 2m 34s")

### Existing Files to Modify

#### `service/TransferForegroundService.kt`
- Inject `TransferSessionRepository`
- Record `startTimeMs` before transfer loop
- After `Done` state, compute `totalBytes` and `durationMs`, save session:

```kotlin
scope.launch(Dispatchers.IO) {
    sessionRepository.saveSession(TransferSession(
        id = startTimeMs.toString(),
        dateMillis = startTimeMs,
        transferred = done.transferred,
        skipped = done.skipped,
        failed = done.failed,
        totalBytes = files.zip(statuses)
            .filter { (_, s) -> s.status == FileStatus.DONE }
            .sumOf { (f, _) -> f.sizeBytes },
        durationMs = System.currentTimeMillis() - startTimeMs,
    ))
}
```

#### `ui/main/MainViewModel.kt`
- Inject `TransferSessionRepository`
- Add `showHistory: StateFlow<Boolean>` and `sessionHistory: StateFlow<List<TransferSession>>`
- Add `openHistory()` (loads sessions, shows sheet) and `closeHistory()`

#### `ui/main/MainScreen.kt`
- Add history `IconButton` in `TopAppBar actions`
- Add `ModalBottomSheet` wrapping `TransferHistorySheet` when `showHistory == true`

---

## Feature 5: Optional Delete from Camera After Transfer

### Goal
A toggle in the picker UI. When enabled and confirmed, `MtpDevice.deleteObject(objectHandle)` is called after each successful import. Delete failure is non-fatal (logged only).

### Files to Modify

#### `domain/model/TransferState.kt`
- Add `deleteAfterTransfer: Boolean = false` to `FilePicker`

#### `ui/main/MainViewModel.kt`
- Add `toggleDeleteAfterTransfer()`:

```kotlin
fun toggleDeleteAfterTransfer() {
    val current = _state.value as? TransferState.FilePicker ?: return
    _state.value = current.copy(deleteAfterTransfer = !current.deleteAfterTransfer)
}
```

#### `data/mtp/MtpTransferRepository.kt`
- Add `deleteAfterTransfer: Boolean = false` to `transferFiles()` signature
- After successful import, if flag is set:

```kotlin
if (success && deleteAfterTransfer) {
    try {
        device.deleteObject(file.objectHandle)
    } catch (e: Exception) {
        Log.w(TAG, "deleteObject failed for ${file.name}", e)
        // non-fatal — local file is safe
    }
}
```

#### `domain/usecase/TransferFilesUseCase.kt`
- Add `deleteAfterTransfer: Boolean = false` parameter, pass through to repository

#### `service/TransferForegroundService.kt`
- Add `deleteAfterTransfer: Boolean = false` to `startTransfer()`, pass to use case

#### `MainActivity.kt`
- Add `private var pendingDeleteMode: Boolean = false`
- Read from `FilePicker.deleteAfterTransfer` when user triggers transfer
- Pass to `service.startTransfer(files, pendingDeleteMode)` in `ServiceConnection.onServiceConnected`

#### `ui/main/MainScreen.kt`
- Add `Switch` row in `FilePickerContent` for the delete toggle
- Add `AlertDialog` confirmation when user taps Transfer with delete mode on:
  - Title: "Delete files from camera?"
  - Body: warn that files are permanently deleted from SD card
  - Buttons: "Delete & Transfer" / "Cancel"
- `FilePickerContent` gains new param `onToggleDeleteMode: () -> Unit`

---

## Risk Notes

| Feature | Risk | Mitigation |
|---|---|---|
| MP4 / CameraFile rename | High blast radius (8+ files) | Implement atomically, compile-check before moving on |
| `deleteObject` rejection | Camera may refuse in some USB modes | Wrap in try/catch, never change file status to ERROR on delete failure |
| `getStorageInfo` returns -1 | Some cameras don't report free space | Guard with `if (freeSpace >= 0)` before showing |
| JSON file concurrency | History button pressed while transfer finishing | Use `Mutex` around file read/write in `TransferSessionRepository` |
| POST_NOTIFICATIONS (Android 13+) | User may not have granted permission | `manager.notify()` silently no-ops — no crash risk; add runtime request in `MainActivity.onCreate` if not already present |
