# Privacy Policy for CR3 Transfer

**Last Updated:** May 13, 2026

## Overview

CR3 Transfer ("the App") is committed to protecting your privacy. This Privacy Policy explains how the App collects, uses, and protects your information.

## 1. What Data We Collect

### Camera File Metadata
The App reads file information from your Canon EOS R8 camera via USB-C connection, including:
- File names (e.g., IMG_1234.CR3)
- File sizes
- File timestamps (creation dates)
- File format/type

**Important:** The App never accesses file contents beyond what is necessary to identify CR3 and video files.

### Transfer History
The App stores a local record of:
- Names of transferred files (for deduplication purposes)
- Transfer dates and timestamps
- Number of files successfully transferred

This data is stored **entirely on your device** using Android DataStore.

### Camera Identity
The App stores optional user-defined labels for your camera:
- Camera nickname (e.g., "Studio R8")
- Camera serial number (used internally for unique identification)

This allows you to manage transfers from multiple cameras.

## 2. How We Use Your Data

The App uses collected data **only** for the following purposes:

1. **File Transfer:** To copy CR3 RAW files and MP4 videos from your camera to your device
2. **Deduplication:** To avoid re-importing files you've already transferred
3. **Progress Tracking:** To display transfer progress during file operations
4. **History Logging:** To maintain a record of transfer sessions in the app's local storage

**We do not use this data for:**
- Analytics or tracking
- Marketing or advertising
- Building user profiles
- Selling or sharing with third parties
- Any purpose other than the core functionality of the App

## 3. No External Networking

The App is **completely offline-first.** It:
- ✅ Requires no internet connection to function
- ✅ Does not send any data to external servers
- ✅ Does not use cloud services
- ✅ Does not integrate with analytics platforms
- ✅ Does not make any network requests

All operations happen on your device and your camera only.

## 4. Data Storage

### Local Storage
All data is stored on your Android device using:
- **Android DataStore** for preferences and transfer history
- **Device file system** for imported CR3 and MP4 files

### Transferred Files
Imported files are saved to:
```
/sdcard/Pictures/CanonImports/[Camera Name]/[Date]/IMG_XXXX.CR3
```

You have full control over these files and can delete them at any time.

### Data Retention
- Transfer history: Kept indefinitely until manually cleared by the user
- Camera names: Kept indefinitely until manually changed or cleared
- Transferred file records: Kept indefinitely to prevent duplicate imports

## 5. Permissions Requested

The App requests the following permissions and uses them for these reasons:

| Permission | Purpose |
|-----------|---------|
| `MANAGE_EXTERNAL_STORAGE` | Access to Pictures folder for saving imported files |
| `READ_EXTERNAL_STORAGE` | Reading saved files for Lightroom import |
| `FOREGROUND_SERVICE` | Keep transfer running and display progress notification |
| `FOREGROUND_SERVICE_DATA_SYNC` | Classify the foreground service as data sync type |
| `POST_NOTIFICATIONS` | Send transfer progress and completion notifications |
| USB Host permissions | Communicate with Canon camera via USB-C |

## 6. Camera Connection

When you connect your Canon EOS R8:
1. The App requests USB permission from Android
2. The App communicates directly with the camera via the MTP (Media Transfer Protocol)
3. No data leaves your device or camera
4. The connection is terminated when:
   - You disconnect the camera
   - You close the App
   - The device auto-disconnects due to inactivity

## 7. Third-Party Services

The App include integration with:
- **Lightroom Mobile:** When you choose to import to Lightroom, the App opens Lightroom with the file URIs. No data is sent to Adobe except what Lightroom normally collects.

The App does not integrate with any other third-party services.

## 8. Data Security

Your data is protected by:
- ✅ No transmission over networks
- ✅ Local encryption via device storage (if enabled on your device)
- ✅ Direct USB communication with camera (no intermediaries)
- ✅ No cloud synchronization or backup

## 9. Children's Privacy

The App is not intended for children under 13. We do not knowingly collect information from children under 13. If you believe we have collected information from a child, please contact us immediately.

## 10. Changes to This Privacy Policy

If we make material changes to this Privacy Policy, we will update this page and indicate the date of the last update. Your continued use of the App constitutes your acceptance of the updated Privacy Policy.

## 11. Contact Us

If you have questions or concerns about this Privacy Policy, please contact:

**Email:** akopbabadzanan@gmail.com  
**GitHub:** https://github.com/Akopmm/canon-mtp-direct

## 12. Additional Notes

### What We Collect vs. What We Don't

**We Collect:**
- ✅ File metadata from your camera (names, sizes, dates)
- ✅ Transfer history (stored locally on your device)
- ✅ Camera names you choose to set
- ✅ Timestamps of operations

**We Don't Collect:**
- ❌ Personal information about you
- ❌ Location data
- ❌ Device identifiers (IMEI, IMSI, etc.)
- ❌ Usage patterns or analytics
- ❌ File contents (only metadata)
- ❌ Network logs
- ❌ Crash reports
- ❌ Advertising tracking data

### GDPR & Privacy Regulations

This App is designed with privacy by default:
- No personal data processing
- No cross-border data transfers
- No third-party data sharing
- Full user control over stored data
- Easy data deletion (clear app data)

---

**This Privacy Policy is effective as of the date listed above and applies to all versions of the CR3 Transfer app.**
