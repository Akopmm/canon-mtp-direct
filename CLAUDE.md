# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android app that transfers RAW files from a Canon camera via USB using the `android.mtp` API directly, bypassing Android's 99-file SAF picker limit. Fully offline — no networking.

Two generations of Canon body are in scope: Digic 8 and newer (EOS R8, R, M50, 90D…) write `.CR3` and `.MP4`, everything before that (EOS 760D/Rebel T6s, 80D, 5D III…) writes `.CR2` and `.MOV`. Tested on hardware: EOS R8 (maintainer), EOS R7 (contributor, #11) and EOS 760D (contributor, #8).

## Build & Development

This is an Android project using Gradle. Once scaffolded:

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug           # Install on connected device
./gradlew lint                   # Run lint checks
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests (device required)
```

No emulator support for USB Host mode — MTP testing requires a physical device with a Canon camera connected over USB. Logic that can be tested without hardware (e.g. preview extraction in `util/ThumbnailUtils`) belongs in `app/src/test` and must stay free of Android framework calls.

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
domain/model/      → CameraFile (objectHandle, name, sizeBytes, dateCreated, fileType), TransferState (sealed class)
domain/usecase/    → ScanCameraUseCase, TransferFilesUseCase
service/           → TransferForegroundService (keeps transfer alive, posts progress notification)
receiver/          → UsbReceiver (ACTION_USB_DEVICE_ATTACHED)
ui/main/           → MainScreen (single Compose screen), MainViewModel
ui/components/     → FileProgressItem, OverallProgressBar, CameraSetupGuide
```

## Critical Implementation Rules

### MTP calls must run on Dispatchers.IO
All MTP methods (`getStorageIds`, `getObjectHandles`, `getObjectInfo`, `importFile`) return `null` on failure instead of throwing. Treat `null` as error. Never call on Main thread.

### File-type detection by filename only
Canon bodies do not reliably report RAW format codes via `MtpObjectInfo.getFormat()`. Always classify by extension (case-insensitive) after `getObjectInfo()` — `.CR3`, `.CR2`, `.JPG`/`.JPEG`, `.HIF`/`.HEIF`/`.HEIC`, `.MP4`, `.MOV`.

Branch on `FileType.isVideo` / `isRaw` rather than naming individual types, so an added format doesn't have to be chased through the UI.

### File enumeration is recursive
Canon bodies organize files in DCIM subfolders. Walk `getObjectHandles` recursively — enter `FORMAT_ASSOCIATION` (folders), collect the supported extensions.

### Use importFile(), never getObject()
`getObject()` loads entire file into memory. Use `MtpDevice.importFile(objectHandle, destFile)` instead.

### Dedup by filename only
Key is filename (e.g. `IMG_1234.CR3`), not full path — Canon reuses folder names across sessions. Store in DataStore as `Set<String>`.

### Destination path
`data/imports/ImportStore` is the only place that knows where imports live — never build an import path anywhere else. The user picks the root (`domain/imports/ImportLocation`): by default photos go to `DCIM/CanonImports/<camera>/YYYY-MM-DD/` and videos to `Movies/CanonImports/<camera>/YYYY-MM-DD/` (`<camera>` = nickname or sanitised USB serial, see `MtpDeviceManager.sanitizeFolderName`); the date is the import day or, optionally, the capture day. Custom roots are plain paths on primary storage (written with File, thanks to All files access) — the folder picker only chooses the path. Every root ever used stays in `KnownRoots`, so dedup and the hand-off still see older imports. Create date subfolder with `File.mkdirs()`. After saving, call `MediaScannerConnection.scanFile()` with the MIME for the type (`image/x-canon-cr3`, `image/x-canon-cr2`, `video/quicktime`, …).

### RAW thumbnails differ by container
When the camera's MTP thumbnail is missing or won't decode, the preview has to come out of the file. CR3 is ISOBMFF with the preview inline near the start — scan the head. CR2 is a TIFF whose preview bytes live far past any header window — parse the IFD chain (`ThumbnailUtils.findCr2PreviewRange`) and fetch only the byte range it reports. Never pull a multi-MB full-size preview per tile when the small one is described.

### Foreground Service
Must call `startForeground()` within 5 seconds of starting. Expose progress as `StateFlow<TransferState>`.

### USB permission
Use `ActivityResultLauncher` with `PendingIntent` — never raw BroadcastReceiver registration inside Activity.

### MTP device lifecycle
One `MtpDevice` instance per connection. Always close in `finally` block or on `ACTION_USB_DEVICE_DETACHED`. Never cache across disconnect/reconnect.

## Canon Specifics

- **Vendor ID:** `0x04A9` (Canon) — `device_filter.xml` matches the vendor, not a product ID, so any Canon body auto-launches the app
- Single SD card slot on the supported bodies — `getStorageIds()` returns one storage ID
- Auto power-off kills connection — remind users to disable it

### EOS R bodies
- User must manually set: `Menu → Communication settings → Choose USB connection app → Photo Import/Remote Control`

### Pre-R DSLRs (EOS 760D and similar)
- No USB-connection-app menu exists; they present PTP as soon as they are plugged in
- **Wi-Fi disables the USB port** on these bodies — `Menu → Set-up 3 → Wi-Fi/NFC → Disable` or the phone sees nothing
- Mini-USB port, so the user needs a USB-C OTG adapter as well as the camera's cable
- `MtpObjectInfo.getDateCreated()` can come back empty — fall back to the modification date or the picker sorts as 1970

## Error Handling Philosophy

Never crash on MTP null returns. Degrade gracefully:
- `open()` fails → show CameraSetupGuide
- `getObjectHandles()` null → "Try unplugging and reconnecting"
- `importFile()` false → mark file as error, continue with remaining files
- USB detached mid-transfer → stop service, show interruption message
- Low storage → check `StatFs` before starting, show required vs available space

### Star ratings come from the file header
In-camera ratings (`ThumbnailUtils.extractRating`) are read from the RAW header: the XMP `xmp:Rating` packet, or Exif tag `0x4746`. A CR3 keeps that XMP after the `moov` box, so its offset follows the embedded thumbnail size — measured 18–30 KB on an RP, 27–39 KB on an R50, 51–67 KB on an R8 — hence a 128 KB read. Read ratings one file at a time after the thumbnails, never in parallel and never during a transfer.

## Out of Scope (v1)

No Wi-Fi transfer, SD card destination, RAW preview, cloud backup, or Settings screen. (Delete-after-transfer and per-camera folders have since been added.) Don't add a second "copy to another folder" step after the import — choosing where imports land is the planned direction.
