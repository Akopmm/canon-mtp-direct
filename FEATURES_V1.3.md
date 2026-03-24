# Features V1.3

## Overview

One feature: display the Canon EOS R8's total lifetime shutter count by querying a
Canon vendor-specific MTP device property directly — no file transfer required.

---

## Feature 1 — Camera Body Shutter Count via MTP Vendor Property

### Goal

After the camera connects, fetch and display the body's total shutter actuations
(e.g. "Shutter count: 4,821") on the main screen below the camera name. Updates once
per connection; does not poll.

---

### Why It's Hard

The MTP specification has no standard property for shutter count. Canon exposes it via
a **vendor-specific `getDevicePropValue` operation** using a proprietary property code
that Canon has never publicly documented.

The property code must be discovered by sniffing PTP/MTP USB traffic between a laptop
running Canon EOS Utility and the R8, then confirming the response decodes to a
plausible integer.

---

### Step 0 — Discovery (manual, one-time, before any code is written)

This step cannot be automated. It requires physical hardware.

**Equipment needed:**
- Canon EOS R8 connected via USB-C to a macOS or Windows laptop
- Canon EOS Utility installed and able to connect to the camera
- A USB packet capture tool:
  - macOS: [Wireshark](https://www.wireshark.org/) with `usbpcap` or `tcpdump` on the
    `usbmon` interface (requires a Linux VM or macOS kernel extension)
  - Windows: [USBPcap](https://desowin.org/usbpcap/) + Wireshark (simplest option)
  - Linux: `sudo modprobe usbmon`, then Wireshark on `usbmonN`

**Procedure:**
1. Start capture before connecting the camera.
2. Open EOS Utility and let it connect fully.
3. Navigate to any screen that displays the shutter count (Camera settings /
   "Camera information" panel in EOS Utility).
4. Stop capture. Filter by `ptp` protocol in Wireshark.
5. Look for `GetDevicePropValue` requests. Each has a 2-byte property code.
6. Find the response whose decoded value matches the shutter count shown in EOS Utility.
7. Note the **property code** (e.g. `0xD303`) and the **response data type**
   (likely `uint32`).

**Known starting points from community reverse-engineering:**
- `0xD303` — reported as shutter count on some Canon DSLRs (unconfirmed for R8)
- `0x500D` — `ShutterSpeed` (standard, not what we want — useful as a sanity check)
- EOS Utility traffic typically uses Canon's vendor extension opcode `0x9101`
  (`GetDeviceInfoEx`) on first connect; shutter count may be a field inside that
  response blob rather than a standalone `GetDevicePropValue` call

Record the property code before proceeding. Everything below assumes it is known.

---

### Implementation

#### `data/mtp/MtpDeviceManager.kt`

Add a suspend function that calls `MtpDevice.getDeviceProperty()` with the discovered
vendor property code:

```kotlin
// Replace 0xD303 with the confirmed property code from Step 0.
private val CANON_PROP_SHUTTER_COUNT = 0xD303

suspend fun getShutterCount(): Int? = withContext(Dispatchers.IO) {
    val device = mtpDevice ?: return@withContext null
    try {
        // MtpDevice.getDeviceProperty is @hide on older SDKs.
        // Use reflection if not directly accessible at minSdk 26.
        val method = device.javaClass.getMethod(
            "getDeviceProperty", Int::class.java, IntArray::class.java
        )
        val out = IntArray(1)
        val result = method.invoke(device, CANON_PROP_SHUTTER_COUNT, out) as Int
        if (result == 0) out[0] else null   // 0 = MTP_RESPONSE_OK
    } catch (e: Exception) {
        Log.w(TAG, "getShutterCount failed", e)
        null
    }
}
```

> **Note on reflection:** `MtpDevice.getDeviceProperty(int, int[])` is present in AOSP
> but marked `@hide`. It is accessible via reflection on API 26+ (minSdk for this app).
> If it disappears in a future SDK, the fallback is to send a raw MTP operation via
> `MtpDevice.sendObject` / the undocumented `MtpDevice.submitRequestAndGetResponse`
> path — more complex but possible.

---

#### `domain/model/TransferState.kt`

Add an optional field to `CameraConnected` (or whichever state represents a live
connection before scanning):

```kotlin
data class CameraConnected(
    val deviceName: String,
    val shutterCount: Int? = null,   // null = not yet fetched / unavailable
) : TransferState()
```

---

#### `ui/main/MainViewModel.kt`

After the camera connects and `CameraConnected` state is set, launch a coroutine to
fetch and update:

```kotlin
viewModelScope.launch {
    val count = deviceManager.getShutterCount()
    val current = _state.value
    if (current is TransferState.CameraConnected) {
        _state.value = current.copy(shutterCount = count)
    }
}
```

---

#### `ui/main/MainScreen.kt`

In `CameraConnectedContent`, below the camera name:

```kotlin
state.shutterCount?.let { count ->
    Text(
        text = "Shutter count: ${"%,d".format(count)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}
```

If `shutterCount` is `null` (property unsupported or fetch failed), nothing is shown —
no error state needed.

---

### Risk Notes

| Risk | Likelihood | Mitigation |
|---|---|---|
| Property code differs on R8 vs. other Canon bodies | Medium | Must confirm via capture in Step 0; do not hard-code a guess |
| `getDeviceProperty` reflection breaks on future Android | Low (API stable since 26) | Wrap in try/catch; feature silently disappears rather than crashing |
| Canon firmware reports 0 or garbage value | Low | Sanity-check: only display if `count > 0 && count < 500_000` |
| `getDeviceInfoEx` blob format (if shutter count is inside it) | High complexity | If standalone `GetDevicePropValue` fails, parse the `0x9101` response — defer to a follow-up |

---

### Implementation Order

1. Complete Step 0 (USB capture) — **blocks everything else**
2. `MtpDeviceManager.getShutterCount()`
3. `TransferState` field addition
4. `MainViewModel` fetch-and-update
5. `MainScreen` display
