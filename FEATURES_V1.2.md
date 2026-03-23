# Features V3

## Overview

Three improvements: folder-scoped Lightroom import, persistent import access when
the camera is disconnected, and faster/streaming MTP scan.

---

## Feature 1 — Folder Selection for Lightroom Import

### Problem
The "Import to Lightroom" button sends **all** `.CR3` files ever transferred under
`DCIM/CanonImports/` to Lightroom. Users who transfer across multiple days get every
session dumped at once with no way to scope to a single shoot.

### Solution
Before firing the intent, show an `AlertDialog` listing the date-named subfolders
found inside `CanonImports/` (e.g. `2026-03-23`, `2026-03-20`). Each folder is a
checkbox row. The most recent folder is pre-selected. The user picks one or more
folders, then confirms.

### Implementation

**`findImportFolders(): List<File>`**
Scans `DCIM/CanonImports/` for immediate subdirectories that contain at least one
`.CR3` file. Returns them sorted newest first (ISO date names sort lexicographically,
so `sortedDescending()` gives most-recent-first).

**`launchLightroomImport(context, folders)`**
Collects `.CR3` files from the selected folders only. Wraps each in a `FileProvider`
URI, fires `Intent.ACTION_SEND_MULTIPLE` targeting `com.adobe.lrmobile`. Falls back
to system chooser if Lightroom is not installed.

**`FolderPickerDialog` composable**
`AlertDialog` with a `LazyColumn` of checkbox rows (one per date folder). Confirm
button is disabled until at least one folder is checked. Default: most recent folder
pre-selected.

Both `DoneContent` and `IdleContent` share the same dialog and helpers.

---

## Feature 2 — Lightroom Import When Camera Is Not Connected

### Problem
The "Import to Lightroom" button only appears after a successful transfer in the
current session (`Done` state). Once the user disconnects the camera and the app
returns to `Idle`, there is no way to re-import previously transferred files without
reconnecting the camera.

### Solution
`IdleContent` checks the filesystem for existing CanonImports folders on mount. If
any date folders containing `.CR3` files are found, a secondary section appears below
the "connect your camera" prompt with the same "Import to Lightroom" button and
folder selection dialog.

### Implementation

- `IdleContent` becomes stateful: a `LaunchedEffect(Unit)` calls `findImportFolders()`
  on `Dispatchers.IO` and stores results in local `remember` state.
- If `importFolders` is non-empty: render `HorizontalDivider` + subtitle
  ("Previously transferred files available") + "Import to Lightroom" button.
- Tapping the button opens the same `FolderPickerDialog` from Feature 1.
- No ViewModel changes required — purely UI + filesystem.

---

## Feature 3 — Faster MTP Scan: Flat Enumeration + Streaming UI

### Why Scanning Takes 15–30 Seconds

The current `MtpFileEnumerator` is recursive and serial:

```
getObjectHandles(root)           →  1 USB call
  getObjectInfo(each handle)     →  1 call per item
  if folder → recurse:
    getObjectHandles(folder)     →  1 call per folder
      getObjectInfo(each child)  →  1 call per file
```

Each `getObjectInfo()` is a **synchronous USB MTP round-trip**.
EOS R8 round-trip latency ≈ 50–100 ms (USB full-speed + camera firmware).
300–500 files × ~60 ms avg = **~20–30 s total**.

Parallelisation is not possible — `MtpDevice` is a single USB channel and is not
thread-safe.

### Solution A — Flat Enumeration (reduce `getObjectHandles` calls)

On first call use `getObjectHandles(storageId, format=0, parentHandle=0)`.
Per the MTP spec, `parentHandle=0` (MTP_OBJECT_HANDLE_ALL) should return all objects
in storage on compliant firmware. If the result contains any non-folder handles (files
ending in `.CR3` or `.MP4`), flat mode is active — skip all recursion.

If the first call returns only folders (EOS R8 may treat `parentHandle=0` as
"root only"), automatically fall back to the existing recursive algorithm.

**Savings**: eliminates N_folders extra `getObjectHandles` round-trips (~5–10 calls
saved). Small but safe optimization.

### Solution B — Streaming UI (main perceived-performance win)

Change `enumerateCameraFilesFlow()` to return `Flow<CameraFile>`, emitting each
file as it is discovered (not after the full scan completes).

`ScanCameraUseCase` returns `Flow<CameraFile>`.
`MainViewModel.scanCamera()` collects the flow, updating
`TransferState.Scanning(discoveredCount)` on every emission so the UI shows:

> *"Scanning camera… 47 files found"*

The counter ticks up in real time. The user sees progress instead of a frozen spinner.
Wall-clock scan time is unchanged; perceived responsiveness improves dramatically.

### Files Changed

| File | What Changes |
|---|---|
| `TransferState.kt` | `data object Scanning` → `data class Scanning(val discoveredCount: Int = 0)` |
| `MtpFileEnumerator.kt` | Replace `enumerateCameraFiles()` with `enumerateCameraFilesFlow(): Flow<CameraFile>`; flat-first with recursive fallback |
| `ScanCameraUseCase.kt` | Return `Flow<CameraFile>` with `.flowOn(Dispatchers.IO)` |
| `MainViewModel.kt` | `scanCamera()` collects flow; updates `Scanning.discoveredCount` per emission |
| `MainScreen.kt` | `ScanningContent` shows live count; `IdleContent` and `DoneContent` updated for Features 1 & 2 |

### Expected Outcome

- **Perceived first response**: ~200 ms after connection (first file emitted almost immediately).
- **Actual total scan time**: unchanged for large cards; marginally faster on firmware
  that supports flat enumeration.
- **UX**: "Scanning camera… 312 files found" counting up vs. a static 20-second spinner.
