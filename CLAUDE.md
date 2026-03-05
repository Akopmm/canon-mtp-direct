# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android app that transfers `.CR3` RAW files from a Canon EOS R8 camera via USB-C using the `android.mtp` API directly, bypassing Android's 99-file SAF picker limit. Fully offline — no networking.

## Build & Development

This is an Android project using Gradle. Once scaffolded:

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug           # Install on connected device
./gradlew lint                   # Run lint checks
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests (device required)
```

No emulator support for USB Host mode — MTP testing requires a physical device with a Canon EOS R8 connected via USB-C.

## Tech Stack & Constraints

- **Language:** Kotlin only (no Java)
- **UI:** Jetpack Compose + Material 3
- **Min SDK 26 / Target SDK 35**
- **Architecture:** MVVM + Clean Architecture with Hilt DI
- **Async:** Kotlin Coroutines + Flow
- **USB/MTP:** `android.hardware.usb` + `android.mtp`
- **Transfer service:** Foreground Service with `FOREGROUND_SERVICE_TYPE_DATA_SYNC`
- **Persistence:** DataStore (preferences) for dedup — stores set of transferred filenames

**Do not add** Room, Retrofit, or any networking library. Single-screen app — no `NavHost` or `BottomSheetScaffold`.

## Architecture

```
data/mtp/          → MTP device management, file enumeration, transfer (emits progress Flow)
data/prefs/        → DataStore for transferred filename dedup
domain/model/      → Cr3File (objectHandle, name, sizeBytes, dateCreated), TransferState (sealed class)
domain/usecase/    → ScanCameraUseCase, TransferFilesUseCase
service/           → TransferForegroundService (keeps transfer alive, posts progress notification)
receiver/          → UsbReceiver (ACTION_USB_DEVICE_ATTACHED)
ui/main/           → MainScreen (single Compose screen), MainViewModel
ui/components/     → FileProgressItem, OverallProgressBar, CameraSetupGuide
```

## Critical Implementation Rules

### MTP calls must run on Dispatchers.IO
All MTP methods (`getStorageIds`, `getObjectHandles`, `getObjectInfo`, `importFile`) return `null` on failure instead of throwing. Treat `null` as error. Never call on Main thread.

### CR3 detection by filename only
The EOS R8 does not reliably report CR3 format codes via `MtpObjectInfo.getFormat()`. Always filter by `.CR3` extension (case-insensitive) after `getObjectInfo()`.

### File enumeration is recursive
EOS R8 organizes files in DCIM subfolders. Walk `getObjectHandles` recursively — enter `FORMAT_ASSOCIATION` (folders), collect `.CR3` files.

### Use importFile(), never getObject()
`getObject()` loads entire file into memory. Use `MtpDevice.importFile(objectHandle, destFile)` instead.

### Dedup by filename only
Key is filename (e.g. `IMG_1234.CR3`), not full path — Canon reuses folder names across sessions. Store in DataStore as `Set<String>`.

### Destination path
Save to `/sdcard/Pictures/CanonImports/YYYY-MM-DD/IMG_XXXX.CR3`. Create date subfolder with `File.mkdirs()`. After saving, call `MediaScannerConnection.scanFile()` with MIME `"image/x-canon-cr3"`.

### Foreground Service
Must call `startForeground()` within 5 seconds of starting. Expose progress as `StateFlow<TransferState>`.

### USB permission
Use `ActivityResultLauncher` with `PendingIntent` — never raw BroadcastReceiver registration inside Activity.

### MTP device lifecycle
One `MtpDevice` instance per connection. Always close in `finally` block or on `ACTION_USB_DEVICE_DETACHED`. Never cache across disconnect/reconnect.

## Canon EOS R8 Specifics

- **Vendor ID:** `0x04A9` (Canon)
- User must manually set: `Menu → Communication settings → Choose USB connection app → Photo Import/Remote Control`
- Single SD card slot — `getStorageIds()` returns one storage ID
- Auto power-off kills connection — remind users to disable it
- App auto-launches via `device_filter.xml` with `vendor-id="0x04A9"`; verify product ID for R8 specifically

## Error Handling Philosophy

Never crash on MTP null returns. Degrade gracefully:
- `open()` fails → show CameraSetupGuide
- `getObjectHandles()` null → "Try unplugging and reconnecting"
- `importFile()` false → mark file as error, continue with remaining files
- USB detached mid-transfer → stop service, show interruption message
- Low storage → check `StatFs` before starting, show required vs available space

## Out of Scope (v1)

No Wi-Fi transfer, SD card destination, RAW preview, delete-after-transfer, multi-camera support, cloud backup, or Settings screen.
