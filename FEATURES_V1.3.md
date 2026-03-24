# Features V1.3

## Overview

Eight improvements across the picker, transfer, and app configuration:
total selection size, date-based selection shortcuts, transfer speed display,
post-transfer verification, EXIF overlay in the file picker, grid density toggle,
file rename templates, and a Settings screen.

---

## Implementation Order

Features must be implemented in this order due to dependencies:

1. Feature 4 (Total size on button) — trivial, self-contained
2. Feature 3 (Select by date) — trivial, self-contained
3. Feature 8 (Grid density toggle) — trivial, self-contained
4. Feature 1 (Transfer speed) — self-contained in service/state
5. Feature 2 (Verify after transfer) — additive to transfer loop
6. Feature 7 (Settings screen) — foundation for Features 5 and 6
7. Feature 5 (File rename) — depends on Settings for template storage
8. Feature 6 (EXIF overlay) — independent but requires new library dep

---

## Feature 1 — Transfer Speed Display

### Problem
During transfer the user sees a progress bar and file count but no speed readout.
On a slow USB connection or large card there is no feedback about whether the
transfer is healthy or stalled.

### Solution
Show a live MB/s readout in the `Transferring` state, updated after each file
completes. Display as `"3.2 MB/s"` next to the current filename.

### Files to Modify

#### `domain/model/TransferState.kt`
Add `transferSpeedMbps: Double? = null` to `Transferring`:

```kotlin
data class Transferring(
    val totalFiles: Int,
    val completedFiles: Int,
    val currentFileName: String,
    val fileStatuses: List<FileTransferStatus>,
    val transferSpeedMbps: Double? = null,
) : TransferState()
```

#### `data/mtp/MtpTransferRepository.kt`
Track elapsed time and bytes per file. After each successful `importFile()`:

```kotlin
val elapsedMs = System.currentTimeMillis() - fileStartMs
val speedMbps = if (elapsedMs > 0) file.sizeBytes / 1_048_576.0 / (elapsedMs / 1000.0) else null
```

Emit `speedMbps` as part of `TransferProgress`. Use a rolling average over the
last 3 files to smooth spikes:

```kotlin
recentSpeeds.add(speedMbps)
if (recentSpeeds.size > 3) recentSpeeds.removeFirst()
val smoothedSpeed = recentSpeeds.average()
```

#### `service/TransferForegroundService.kt`
Forward `speedMbps` into the `Transferring` state copy and the progress
notification subtitle.

#### `ui/main/MainScreen.kt`
In `TransferringContent`, show below the progress bar:

```kotlin
state.transferSpeedMbps?.let { speed ->
    Text(
        text = String.format("%.1f MB/s", speed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
```

---

## Feature 2 — Verify After Transfer

### Problem
A failed or interrupted `importFile()` call can produce a truncated file with no
error — the destination file exists but is smaller than the source. Currently the
app only checks `file.exists() && file.length() > 0`, which misses partial writes.

### Solution
After each `importFile()` call, compare the destination file's size to
`CameraFile.sizeBytes`. If they don't match, mark the file as `ERROR` and log the
discrepancy. No UI changes needed — the existing `FileStatus.ERROR` state already
shows in the transfer list.

### Files to Modify

#### `data/mtp/MtpTransferRepository.kt`
Replace the current post-transfer size check:

```kotlin
// Before
val success = destFile.exists() && destFile.length() > 0

// After
val destSize = destFile.length()
val success = destFile.exists() && destSize == file.sizeBytes
if (destFile.exists() && destSize != file.sizeBytes) {
    Log.w(TAG, "Size mismatch for ${file.name}: expected ${file.sizeBytes}, got $destSize")
}
```

No other files need changing.

---

## Feature 3 — Select by Date

### Problem
The picker only has "All" and "None" selection shortcuts. A user who shot across
multiple days must manually tap individual files to scope to today's shoot.

### Solution
Add two buttons alongside All / None: **"Today"** and **"This week"**. Each selects
files whose `dateCreated` falls within the relevant window.

### Files to Modify

#### `ui/main/MainViewModel.kt`
Add two functions:

```kotlin
fun selectToday() {
    val current = _state.value as? TransferState.FilePicker ?: return
    val startOfDay = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    _state.value = current.copy(
        selectedHandles = current.files
            .filter { it.dateCreated >= startOfDay }
            .map { it.objectHandle }
            .toSet()
    )
}

fun selectThisWeek() {
    val current = _state.value as? TransferState.FilePicker ?: return
    val startOfWeek = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.DAY_OF_WEEK, firstDayOfWeek)
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    _state.value = current.copy(
        selectedHandles = current.files
            .filter { it.dateCreated >= startOfWeek }
            .map { it.objectHandle }
            .toSet()
    )
}
```

#### `ui/main/MainScreen.kt`
Add "Today" and "Week" `TextButton`s in the selection controls row alongside
the existing All / None buttons. Pass `onSelectToday` and `onSelectThisWeek`
as params to `FilePickerContent`.

---

## Feature 4 — Total Size of Selection on Transfer Button

### Problem
The transfer button says "Transfer 23 files" but gives no indication of how much
storage will be used or how long it might take.

### Solution
Show total selected size inline on the button: **"Transfer 23 files · 1.4 GB"**.
Computed from `selectedHandles` on every selection change — no new state needed.

### Files to Modify

#### `ui/main/MainScreen.kt`
In `FilePickerContent`, compute total bytes from selected files:

```kotlin
val selectedBytes = state.files
    .filter { it.objectHandle in state.selectedHandles }
    .sumOf { it.sizeBytes }

Button(...) {
    Text(
        if (selectedCount > 0)
            "Transfer $selectedCount ${if (selectedCount == 1) "file" else "files"} · ${formatBytes(selectedBytes)}"
        else
            "Transfer"
    )
}
```

Reuse the existing top-level `formatBytes()` helper (remove the "free on camera SD"
suffix — extract a generic version).

---

## Feature 5 — File Rename on Import

### Problem
Canon names files `IMG_0001.CR3`. After importing hundreds of files across sessions
it becomes impossible to tell shoots apart without opening Lightroom.

### Solution
An optional rename template in Settings. When enabled, files are saved with a
user-defined pattern instead of the original name. Default (disabled): keep
original filename.

**Template tokens:**
| Token | Expands to |
|---|---|
| `{date}` | `2026-03-24` (date of capture from `dateCreated`) |
| `{seq}` | Zero-padded sequence number within the current transfer, e.g. `001` |
| `{original}` | Original filename without extension, e.g. `IMG_0001` |
| `{ext}` | File extension, e.g. `CR3` |

**Example templates:**
- `{date}_{seq}.{ext}` → `2026-03-24_001.CR3`
- `{date}_{original}.{ext}` → `2026-03-24_IMG_0001.CR3`
- `{original}.{ext}` → `IMG_0001.CR3` (passthrough, same as disabled)

### New Files to Create

#### `data/prefs/AppSettings.kt`
DataStore-backed settings repository (used by Feature 7 too):

```kotlin
@Singleton
class AppSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore = context.dataStoreSettings  // separate from dedup store

    val renameEnabled: Flow<Boolean> = dataStore.data.map { it[RENAME_ENABLED] ?: false }
    val renameTemplate: Flow<String> = dataStore.data.map {
        it[RENAME_TEMPLATE] ?: "{date}_{seq}.{ext}"
    }

    suspend fun setRenameEnabled(enabled: Boolean) { ... }
    suspend fun setRenameTemplate(template: String) { ... }
}
```

### Files to Modify

#### `data/mtp/MtpTransferRepository.kt`
Inject `AppSettings`. Before writing each file, resolve the destination filename:

```kotlin
val destName = if (renameEnabled) {
    resolveTemplate(template, file, sequenceIndex)
} else {
    file.name
}
```

```kotlin
private fun resolveTemplate(template: String, file: CameraFile, seq: Int): String {
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(file.dateCreated))
    val original = file.name.substringBeforeLast(".")
    val ext = file.name.substringAfterLast(".", "")
    return template
        .replace("{date}", date)
        .replace("{seq}", seq.toString().padStart(3, '0'))
        .replace("{original}", original)
        .replace("{ext}", ext)
}
```

---

## Feature 6 — EXIF Overlay in File Picker

### Problem
All thumbnails look similar at a glance. The user cannot see exposure data (ISO,
aperture, shutter speed) without opening a separate app, making it hard to decide
which files to skip.

### Solution
Tapping a thumbnail opens a bottom sheet showing: filename, file size, capture date,
ISO, aperture (f/), and shutter speed. The sheet is dismissed by tapping outside or
swiping down.

### Dependency
Add `metadata-extractor` to `app/build.gradle.kts`:

```kotlin
implementation("com.drewnoakes:metadata-extractor:2.19.0")
```

This library reads EXIF/MakerNote data from CR3 files (Apache 2.0 licence, ~500 KB).

### New Files to Create

#### `data/exif/ExifReader.kt`

```kotlin
@Singleton
class ExifReader @Inject constructor() {
    data class ExifData(
        val iso: Int?,
        val aperture: String?,   // e.g. "f/2.8"
        val shutterSpeed: String?, // e.g. "1/500s"
    )

    fun read(file: java.io.File): ExifData {
        return try {
            val metadata = ImageMetadataReader.readMetadata(file)
            val exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            ExifData(
                iso = exif?.getInteger(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT),
                aperture = exif?.getString(ExifSubIFDDirectory.TAG_FNUMBER),
                shutterSpeed = exif?.getString(ExifSubIFDDirectory.TAG_EXPOSURE_TIME),
            )
        } catch (_: Exception) {
            ExifData(null, null, null)
        }
    }
}
```

> EXIF is read from already-transferred files in `CanonImports/`, not over MTP.
> For files not yet transferred, the overlay shows only filename, size, and date.

#### `ui/components/FileDetailSheet.kt`
`ModalBottomSheet` composable showing file metadata. If EXIF is available:
ISO, aperture, shutter speed. Always shows: filename, size, capture date.

### Files to Modify

#### `ui/main/MainScreen.kt`
In `FileThumbnail`, add `onLongClick` (or a small info icon overlay). On tap of
the info icon, open `FileDetailSheet` for that file.

#### `ui/main/MainViewModel.kt`
Add `selectedFileForDetail: StateFlow<CameraFile?>`. On info tap, set this;
on sheet dismiss, clear it. EXIF reading happens in a coroutine on Dispatchers.IO,
resolved after the sheet opens (show loading state briefly).

---

## Feature 7 — Settings Screen

### Problem
Several values are hardcoded: destination path (`DCIM/CanonImports`), default
selection behaviour (new files only), screen-on during transfer. Users cannot
change them without a code change.

### Solution
A Settings screen accessible via a gear icon in the `TopAppBar`. Uses a
`ModalBottomSheet` (consistent with history sheet). Backed by `AppSettings`
DataStore introduced in Feature 5.

### Settings to Expose

| Setting | Type | Default | Notes |
|---|---|---|---|
| Destination folder | String | `DCIM/CanonImports` | Shown as path, editable via text field |
| Default selection | Enum (New / All / None) | New | Applied on scan complete |
| Keep screen on during transfer | Boolean | false | Sets `FLAG_KEEP_SCREEN_ON` |
| File rename | Boolean + template | false / `{date}_{seq}.{ext}` | From Feature 5 |
| Grid columns | Int (2 / 3 / 4) | 3 | From Feature 8 |

### New Files to Create

#### `ui/settings/SettingsSheet.kt`
`ModalBottomSheet` with a `LazyColumn` of settings rows. Each row uses the
standard Material 3 `ListItem` composable. Persist changes immediately via
`AppSettings` — no "Save" button needed.

### Files to Modify

#### `ui/main/MainScreen.kt`
Add gear `IconButton` in `TopAppBar` alongside the existing history button.

#### `ui/main/MainViewModel.kt`
Add `showSettings: StateFlow<Boolean>`, `openSettings()`, `closeSettings()`.
Read `defaultSelection` from `AppSettings` in `scanCamera()` to determine
initial `selectedHandles`.

#### `data/mtp/MtpTransferRepository.kt`
Read destination folder from `AppSettings` instead of hardcoded string.

#### `MainActivity.kt`
Read `keepScreenOn` from `AppSettings`; set/clear `FLAG_KEEP_SCREEN_ON` on the
window accordingly when transfer starts/stops.

---

## Feature 8 — Grid Density Toggle

### Problem
The file picker uses a fixed 3-column grid. Users with large cards (300+ files)
may prefer 4 columns; users who want to see thumbnail detail may prefer 2.

### Solution
A segmented control (2 / 3 / 4 columns) in the picker toolbar. Persisted via
`AppSettings` (Feature 7). Falls back to 3 if Settings not yet implemented.

### Files to Modify

#### `domain/model/TransferState.kt`
Add `gridColumns: Int = 3` to `FilePicker`.

#### `ui/main/MainViewModel.kt`
Add `cycleGridColumns()`: cycles 3 → 4 → 2 → 3. Persists to `AppSettings` when
available.

#### `ui/main/MainScreen.kt`
Replace `GridCells.Fixed(3)` with `GridCells.Fixed(state.gridColumns)`.
Add a small columns toggle button (e.g. `Icons.Filled.GridView`) to the
selection controls row. Tapping it calls `onCycleGrid`.

---

## Risk Notes

| Feature | Risk | Mitigation |
|---|---|---|
| Rename template collision | Two files resolve to same name | Append `_2`, `_3` suffix on conflict |
| EXIF read on untransferred files | File doesn't exist locally yet | Show "not yet transferred" in sheet |
| Settings destination path | User enters invalid path | Validate with `File.mkdirs()`; revert to default on failure |
| Verify size mismatch | Canon pads files or reports wrong size via MTP | Log warning but don't block; expose as optional strict mode |
| metadata-extractor CR3 support | Library may not parse all CR3 MakerNote fields | Wrap in try/catch; show "–" for unavailable fields |
