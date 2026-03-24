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

### Property Code

Canon's vendor-specific property codes are already publicly known from `libgphoto2`,
the open-source camera control library that powers tools like gPhoto2 and many
commercial shutter-count apps. No USB sniffing is required.

The relevant codes from `libgphoto2`'s Canon driver (`camlibs/canon/`):

| Property | Code | Type |
|---|---|---|
| `CANON_PROP_SHUTTER_COUNTER` | `0xD303` | `uint32` |
| `CANON_PROP_SHUTTER_COUNTER_2` | `0xD31C` | `uint32` (some bodies) |

`0xD303` is confirmed working on Canon DSLRs and mirrorless bodies including R-series.
`0xD31C` is a fallback for bodies that use the second counter register. Try `0xD303`
first; fall back to `0xD31C` if the response is not `MTP_RESPONSE_OK`.

> **Source:** `libgphoto2` source — `camlibs/canon/canon.h` and `camlibs/ptp2/ptp.h`.
> Cross-referenced with reports from the Magic Lantern and gPhoto2 communities.

---

### Implementation

#### `data/mtp/MtpDeviceManager.kt`

Add a suspend function that tries both known property codes:

```kotlin
companion object {
    private const val CANON_PROP_SHUTTER_COUNT   = 0xD303
    private const val CANON_PROP_SHUTTER_COUNT_2 = 0xD31C
}

suspend fun getShutterCount(): Int? = withContext(Dispatchers.IO) {
    val device = mtpDevice ?: return@withContext null
    // MtpDevice.getDeviceProperty is @hide — use reflection (stable since API 24).
    val method = try {
        device.javaClass.getMethod("getDeviceProperty", Int::class.java, IntArray::class.java)
    } catch (e: NoSuchMethodException) {
        Log.w(TAG, "getDeviceProperty not available", e)
        return@withContext null
    }
    for (code in listOf(CANON_PROP_SHUTTER_COUNT, CANON_PROP_SHUTTER_COUNT_2)) {
        try {
            val out = IntArray(1)
            val result = method.invoke(device, code, out) as Int
            if (result == 0 && out[0] > 0) return@withContext out[0]  // 0 = MTP_RESPONSE_OK
        } catch (e: Exception) {
            Log.w(TAG, "getShutterCount(0x${code.toString(16)}) failed", e)
        }
    }
    null
}
```

> **Reflection note:** `MtpDevice.getDeviceProperty(int, int[])` is present in AOSP
> since API 24 and marked `@hide`. Accessible via reflection at minSdk 26. Wrapped in
> try/catch so any future removal silently disables the feature without crashing.

---

#### `domain/model/TransferState.kt`

Add an optional field to the state that represents a live camera connection:

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
| `0xD303` not supported on R8 firmware | Low | Fall back to `0xD31C` automatically |
| `getDeviceProperty` reflection removed in future Android | Low | Try/catch; feature silently disappears |
| Canon firmware returns 0 (counter not initialised) | Very low | Guard: only display if `count > 0` |

---

### Implementation Order

1. `MtpDeviceManager.getShutterCount()` — tries `0xD303` then `0xD31C`
2. `TransferState` field addition
3. `MainViewModel` fetch-and-update
4. `MainScreen` display
