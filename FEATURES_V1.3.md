# Canon CR3 Transfer — v1.3 Features

## Overview

Three improvements to support multi-camera workflows: camera-specific import folders, user-defined friendly camera names, and nested folder discovery for import history access.

---

## Feature 1 — Camera-Specific Import Folders

### Problem
All cameras transfer files to a single shared `DCIM/CanonImports/YYYY-MM-DD/` folder structure. When using multiple cameras (e.g., studio body + backup body), it is impossible to visually separate which camera shot which files on disk, and dedup logic incorrectly skips files from different cameras if they share the same filename.

### Solution
Each camera is automatically assigned a unique identifier (serial number, product name, or vendor/product ID fallback). Files are organized into camera-specific subfolders:

- **Photos**: `DCIM/CanonImports/{camera_id}/YYYY-MM-DD/`
- **Videos**: `Movies/CanonImports/{camera_id}/YYYY-MM-DD/`

Dedup scanning (checking if a file was already transferred) is scoped to the current camera only—different cameras can now safely transfer files with identical names.

### Implementation

**`MtpDeviceManager.kt`**
- Add `cameraId: String?` property derived from `UsbDevice` (serial number → product name → vendor/product ID)
- Add `cameraName` state to hold user-provided friendly name
- Add `cameraDirectoryName: String?` which returns user name if set, otherwise falls back to `cameraId`
- New method `setCameraName(name: String?)` to save a friendly name with filesystem-safe sanitization

**`MtpTransferRepository.kt`**
- Add `deviceManager: MtpDeviceManager` dependency injection
- Split destination paths to include camera subdirectory:
  - `getPhotoDestDir(dateFolder)` now resolves to `DCIM/CanonImports/{cameraDirectoryName}/{dateFolder}`
  - `getVideoDestDir(dateFolder)` now resolves to `Movies/CanonImports/{cameraDirectoryName}/{dateFolder}`
- `photoOutputDirectory` and `videoOutputDirectory` properties now return camera-scoped roots
- Dedup cache `buildImportedNamesCache()` scans only the current camera's root, preventing cross-camera filename collisions

**Storage hierarchy example**:
```
DCIM/CanonImports/
  studio_r8/
    2026-03-23/
      IMG_0001.CR3
      IMG_0002.CR3
  backup_r5/
    2026-03-23/
      IMG_0001.CR3  ← Different file, same name, no dedup conflict
```

---

## Feature 2 — User-Defined Camera Names

### Problem
The auto-generated camera ID (e.g., `canon_04A9_0123`) is opaque and difficult to distinguish between cameras at a glance. Users cannot customize folder names to reflect their camera's role (e.g., "Studio R8", "Travel R5", "Backup Body").

### Solution
When a camera connects for the first time (or no saved name exists), prompt the user to enter a friendly name. The name is sanitized (spaces→underscores, special chars removed) and persisted to `DataStore`. On future connections, the saved name is automatically applied to the directory structure.

### Implementation

**`CameraNameRepository.kt` (new file)**
- `DataStore<Preferences>` keyed as `camera_names`
- `saveCameraName(cameraId: String, name: String)` — store per camera
- `getCameraName(cameraId: String): String?` — retrieve saved name

**`TransferState.kt`**
- Change `CameraConnected` from singleton to data class:
  ```kotlin
  data class CameraConnected(val cameraName: String? = null) : TransferState
  ```

**`MainViewModel.kt`**
- Inject `CameraNameRepository`
- On `onCameraConnected()`, after opening the MTP device:
  1. Query `CameraNameRepository.getCameraName(cameraId)` for saved name
  2. If found, apply it and proceed to camera scan
  3. If not found, emit `TransferState.CameraConnected(cameraName = null)` to trigger UI prompt
- Add `saveCameraName(name: String)` method to write name to repository and MtpDeviceManager, then proceed to scan

**`MainScreen.kt`**
- Update `CameraConnectedContent()` to accept `cameraName` parameter and callback
- If `cameraName` is null:
  - Show prompt: "Please give this camera a friendly name. This will be used for the import folder."
  - Render `OutlinedTextField` for user input
  - Two buttons: "Use this name" (saves and scans) and "Use default camera id" (skips saving, uses fallback)
- If `cameraName` is non-null:
  - Show progress spinner with "Connected to $cameraName" message

---

## Feature 3 — Smart Nested Folder Discovery for Import History

### Problem
When a camera is disconnected, the `IdleContent` scans for previously transferred files to enable offline Lightroom import. The current implementation only finds top-level date folders in `CanonImports/`. With v1.3's camera-specific folders, this logic breaks—it must now recursively discover nested `{camera_id}/{YYYY-MM-DD}/` structures.

### Solution
Update `findImportFolders()` to use `walkTopDown()` to recursively find all leaf directories (not camera folders) that contain at least one `.CR3` file. Results are sorted by date (newest first) and by camera folder (for consistent ordering).

### Implementation

**`MainScreen.kt`**
```kotlin
private fun findImportFolders(): List<File> {
    val importDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        "CanonImports",
    )
    if (!importDir.exists()) return emptyList()

    return importDir.walkTopDown()
        .filter { dir ->
            dir.isDirectory && dir != importDir &&
                dir.listFiles()?.any { child ->
                    child.isFile && child.name.endsWith(".CR3", ignoreCase = true)
                } == true
        }
        .sortedWith(
            compareByDescending<File> { it.name }  // date descending
                .thenByDescending { it.parentFile?.name }  // camera folder descending
        )
        .toList()
}
```

**Effect**: Now discovers folders at any depth under `CanonImports/`, not just immediate children.

---

## Summary

| Feature | Benefit |
|---------|---------|
| Camera-specific folders | Multi-camera workflows, no cross-device dedup conflicts |
| User-defined names | Intuitive folder names, easier to locate files on disk |
| Nested folder discovery | Offline Lightroom import works after v1.3 upgrade |

---

## v1.3 Changelog

- **New**: `CameraNameRepository` for camera name persistence
- **New**: `cameraDirectoryName` property in `MtpDeviceManager`
- **Modified**: `MtpTransferRepository` destination paths now include camera subdirectory
- **Modified**: `TransferState.CameraConnected` is now a data class holding optional `cameraName`
- **Modified**: `MainScreen` shows camera naming prompt on first connection
- **Modified**: `findImportFolders()` uses recursive `walkTopDown()` to find nested date folders
- **Removed**: No breaking changes to public APIs; fully backward compatible in data layer
