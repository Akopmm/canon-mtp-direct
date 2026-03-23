# Canon CR3 Transfer

Transfer CR3 RAW files from your Canon camera to your Android phone via USB-C — no Wi-Fi, no cloud, no 99-file limit.

Built specifically for the **Canon EOS R8**, but should work with other Canon cameras that support MTP over USB.

## Features

- **Direct USB transfer** — plug camera into phone with USB-C cable
- **No file limit** — transfers all CR3 and MP4 files, bypassing Android's 99-file SAF picker
- **Smart dedup** — skips files already transferred (reinstall-safe, checked against files on disk)
- **Folder-scoped Lightroom import** — choose which date session(s) to import; available even without camera connected
- **Background transfer** — foreground service keeps transfer alive while you use other apps
- **Transfer history** — log of past sessions with file counts, size and duration
- **Public storage** — CR3 to `DCIM/CanonImports/YYYY-MM-DD/`, MP4 to `Movies/CanonImports/YYYY-MM-DD/`

## Download & Install

1. Go to [**Releases**](../../releases) and download the latest `cr3-transfer.apk`
2. On your Android phone, open the APK file
3. If prompted, allow **"Install from unknown sources"** for your browser/file manager
4. Open the app — it will ask for "All files access" permission (needed to save to DCIM)

> **Requires:** Android 8.0+ (API 26) and a phone with USB-C OTG support

## Camera Setup

Before connecting, set your Canon camera to the correct USB mode:

1. **Menu** → **Communication settings** → **Choose USB connection app**
2. Select **Photo Import/Remote Control** (NOT "EOS Utility" or "Register to a smartphone")
3. **Disable auto power-off** — the camera will disconnect if it sleeps during transfer

## Usage

1. Connect your Canon camera to your phone with a USB-C cable
2. The app auto-launches and scans for CR3 files
3. Tap **Start Transfer**
4. When done, tap **Import to Lightroom** or **Open Folder**

Files are saved to: `Internal Storage / DCIM / CanonImports / YYYY-MM-DD /`

## Building from Source

```bash
git clone https://github.com/Akopmm/canon-mtp-direct.git
cd canon-mtp-direct
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK with compileSdk 36. MTP testing requires a physical device — no emulator support for USB Host mode.

## License

MIT

---

☕ If you find this useful, [buy me a coffee](https://buymeacoffee.com/akopmm)!
