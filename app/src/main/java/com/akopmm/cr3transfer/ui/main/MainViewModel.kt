package com.akopmm.cr3transfer.ui.main

import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.akopmm.cr3transfer.data.mtp.MtpDeviceManager
import com.akopmm.cr3transfer.data.mtp.MtpTransferRepository
import com.akopmm.cr3transfer.data.prefs.CameraNameRepository
import com.akopmm.cr3transfer.data.prefs.TransferSessionRepository
import com.akopmm.cr3transfer.domain.model.CameraFile
import com.akopmm.cr3transfer.domain.model.FileType
import com.akopmm.cr3transfer.domain.model.TransferSession
import com.akopmm.cr3transfer.domain.model.TransferState
import com.akopmm.cr3transfer.domain.usecase.ScanCameraUseCase
import com.akopmm.cr3transfer.util.ThumbnailUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val deviceManager: MtpDeviceManager,
    private val scanCameraUseCase: ScanCameraUseCase,
    private val transferRepository: MtpTransferRepository,
    private val cameraNameRepository: CameraNameRepository,
    private val sessionRepository: TransferSessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private val _thumbnails = MutableStateFlow<Map<Int, ByteArray>>(emptyMap())
    val thumbnails: StateFlow<Map<Int, ByteArray>> = _thumbnails.asStateFlow()

    // Handles of HDR-PQ CR3 shots whose HEVC preview can't be decoded on Android (shown with an
    // "HDR" placeholder). See loadEmbeddedCr3Preview.
    private val _hdrHandles = MutableStateFlow<Set<Int>>(emptySet())
    val hdrHandles: StateFlow<Set<Int>> = _hdrHandles.asStateFlow()

    private val _showHistory = MutableStateFlow(false)
    val showHistory: StateFlow<Boolean> = _showHistory.asStateFlow()

    private val _sessionHistory = MutableStateFlow<List<TransferSession>>(emptyList())
    val sessionHistory: StateFlow<List<TransferSession>> = _sessionHistory.asStateFlow()

    private var scannedFiles: List<CameraFile> = emptyList()
    private var isConnecting = false

    val files: List<CameraFile> get() = scannedFiles

    fun selectedFiles(): List<CameraFile> {
        val state = _state.value
        if (state is TransferState.FilePicker) {
            return scannedFiles.filter { it.objectHandle in state.selectedHandles }
        }
        return scannedFiles
    }

    fun onCameraConnected(usbDevice: UsbDevice) {
        val currentState = _state.value
        if (isConnecting) {
            android.util.Log.d("CR3Transfer", "onCameraConnected: skipping, already connecting")
            return
        }
        if (deviceManager.isConnected &&
            (currentState is TransferState.CameraConnected ||
             currentState is TransferState.Scanning ||
             currentState is TransferState.FilePicker ||
             currentState is TransferState.Transferring)) {
            android.util.Log.d("CR3Transfer", "onCameraConnected: skipping, already connected/active state=$currentState")
            return
        }

        isConnecting = true
        viewModelScope.launch {
            try {
                android.util.Log.d("CR3Transfer", "onCameraConnected: opening MTP device...")
                val opened = withContext(Dispatchers.IO) { deviceManager.open(usbDevice) }
                android.util.Log.d("CR3Transfer", "onCameraConnected: open result=$opened")
                if (!opened) {
                    _state.value = TransferState.Error(
                        message = "Could not open camera. Check USB mode setting.",
                        isCameraSetupError = true,
                    )
                    return@launch
                }

                val cameraId = deviceManager.cameraId
                val savedName = if (cameraId != null) {
                    withContext(Dispatchers.IO) { cameraNameRepository.getCameraName(cameraId) }
                } else null
                if (!savedName.isNullOrBlank()) {
                    deviceManager.setCameraName(savedName)
                    scanCamera()
                } else {
                    _state.value = TransferState.CameraConnected(cameraName = null)
                }
            } finally {
                isConnecting = false
            }
        }
    }

    fun onCameraDisconnected() {
        deviceManager.close()
        scannedFiles = emptyList()
        _thumbnails.value = emptyMap()
        _hdrHandles.value = emptySet()
        _state.value = TransferState.Idle
    }

    fun scanCamera() {
        viewModelScope.launch {
            _state.value = TransferState.Scanning()
            val discovered = mutableListOf<CameraFile>()
            try {
                // Set 60-second timeout for scanning (large video files can be slow)
                withTimeoutOrNull(60_000L) {
                    scanCameraUseCase().collect { file ->
                        discovered.add(file)
                        _state.value = TransferState.Scanning(discoveredCount = discovered.size)
                    }
                } ?: run {
                    // Timeout occurred
                    android.util.Log.w("CR3Transfer", "Scan timeout after 60s - ${discovered.size} files discovered")
                    if (discovered.isNotEmpty()) {
                        // Use what we found so far
                        android.util.Log.i("CR3Transfer", "Continuing with ${discovered.size} discovered files")
                    } else {
                        _state.value = TransferState.Error(
                            message = "Camera scan timed out. Try reconnecting or check camera USB mode."
                        )
                        return@launch
                    }
                }
                
                scannedFiles = discovered
                android.util.Log.d("CR3Transfer", "scanCamera: finished with ${scannedFiles.size} files: ${scannedFiles.map { it.name }}")
                if (scannedFiles.isEmpty()) {
                    _state.value = TransferState.Done(transferred = 0, skipped = 0, failed = 0)
                    return@launch
                }
                val newHandles = withContext(Dispatchers.IO) {
                    val cache = transferRepository.buildImportedNamesCache()
                    scannedFiles
                        .filter { !cache.contains(it.name, it.fileType) }
                        .map { it.objectHandle }
                        .toSet()
                }
                android.util.Log.d("CR3Transfer", "scanCamera: creating FilePicker with ${scannedFiles.size} files, ${newHandles.size} selected")
                _state.value = TransferState.FilePicker(
                    files = scannedFiles,
                    selectedHandles = newHandles,
                )
                loadThumbnails(scannedFiles)
                // Fetch camera free space concurrently
                viewModelScope.launch(Dispatchers.IO) {
                    val freeBytes = deviceManager.getCameraFreeBytes()
                    val current = _state.value
                    if (current is TransferState.FilePicker) {
                        _state.value = current.copy(cameraFreeBytes = freeBytes)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CR3Transfer", "Scan failed: ${e.message}", e)
                _state.value = TransferState.Error(
                    message = e.message ?: "Failed to scan camera"
                )
            }
        }
    }

    private fun loadThumbnails(files: List<CameraFile>) {
        _hdrHandles.value = emptySet()
        viewModelScope.launch {
            for (file in files) {
                try {
                    // Primary: the camera's embedded MTP thumbnail.
                    if (loadMtpThumbnail(file)) continue
                    // Fallback (CR3 only): pull the preview JPEG straight out of the file header.
                    // Covers cameras that expose no usable MTP thumbnail for RAW files.
                    if (file.fileType == FileType.CR3) {
                        loadEmbeddedCr3Preview(file)
                    }
                } catch (e: Exception) {
                    // Skip thumbnail for this file if loading fails
                    android.util.Log.w("CR3Transfer", "Failed to load thumbnail for ${file.name}: ${e.message}")
                }
            }
        }
    }

    /** Returns true if a usable thumbnail was stored from the MTP GetThumb operation. */
    private suspend fun loadMtpThumbnail(file: CameraFile): Boolean {
        val thumb = withContext(Dispatchers.IO) {
            // 5-second timeout per thumbnail (large videos might fail)
            withTimeoutOrNull(5_000L) {
                deviceManager.getThumbnail(file.objectHandle)
            }
        }
        if (thumb == null || thumb.isEmpty()) {
            android.util.Log.d(
                "CR3Transfer",
                "No MTP thumbnail for ${file.name} (${deviceManager.getThumbnailDiag(file.objectHandle)})"
            )
            return false
        }
        // Canon CR3 thumbnails may have a proprietary prefix before the JPEG SOI marker. Scan
        // for FF D8 and store the JPEG portion so BitmapFactory decodes clean data (fixes both
        // "QR code" noise and blank placeholders).
        val jpeg = ThumbnailUtils.extractJpeg(thumb)
        if (jpeg == null) {
            android.util.Log.w(
                "CR3Transfer",
                "No JPEG marker in MTP thumbnail for ${file.name} " +
                    "(size=${thumb.size} head=[${ThumbnailUtils.hexPreview(thumb)}] " +
                    "${deviceManager.getThumbnailDiag(file.objectHandle)})"
            )
            return false
        }
        // The R8's HDR-PQ shots report a JFIF thumbnail that is actually a 10-bit HEIF stream —
        // it carries an FF D8 marker but won't decode. Verify it renders before storing so we fall
        // through to the header-preview fallback instead of showing a broken tile.
        if (!decodesToBitmap(jpeg)) {
            android.util.Log.w(
                "CR3Transfer",
                "MTP thumbnail for ${file.name} has FF D8 but won't decode " +
                    "(jpeg=${jpeg.size}B head=[${ThumbnailUtils.hexPreview(jpeg)}] " +
                    "${deviceManager.getThumbnailDiag(file.objectHandle)})"
            )
            return false
        }
        _thumbnails.value = _thumbnails.value + (file.objectHandle to jpeg)
        android.util.Log.d("CR3Transfer", "Loaded MTP thumbnail for ${file.name} (raw=${thumb.size}B jpeg=${jpeg.size}B)")
        return true
    }

    /** Fallback: read the CR3 header via partial MTP read and extract the embedded preview JPEG. */
    private suspend fun loadEmbeddedCr3Preview(file: CameraFile) {
        val head = withContext(Dispatchers.IO) {
            withTimeoutOrNull(8_000L) {
                deviceManager.readObjectHead(file.objectHandle, CR3_HEAD_READ_BYTES)
            }
        }
        if (head == null || head.isEmpty()) {
            android.util.Log.w("CR3Transfer", "No header bytes for CR3 fallback: ${file.name}")
            return
        }
        // Tier 1: a normal CR3 carries a JPEG preview in its header. Only accept a candidate that
        // actually decodes — an HDR-PQ CR3's HEVC binary has stray FF D8…FF D9 runs that aren't JPEG.
        val embedded = ThumbnailUtils.extractEmbeddedJpeg(head, accept = ::decodesToBitmap)
        if (embedded != null) {
            _thumbnails.value = _thumbnails.value + (file.objectHandle to embedded)
            android.util.Log.d("CR3Transfer", "Loaded embedded CR3 preview for ${file.name} (${embedded.size}B from ${head.size}B head)")
            return
        }
        // No JPEG preview means this is an HDR-PQ CR3: its preview is 10-bit HEVC (Range Extensions
        // 4:2:2), which Android's HEVC decoders don't support, so it can't be rendered on-device.
        // Flag it so the grid shows a distinct "HDR" placeholder instead of a generic one. (The file
        // itself still imports fine.)
        _hdrHandles.value = _hdrHandles.value + file.objectHandle
        android.util.Log.i(
            "CR3Transfer",
            "HDR-PQ CR3 ${file.name}: no JPEG preview (HEVC 4:2:2, not Android-decodable) — HDR placeholder"
        )
    }

    /**
     * True if [bytes] decode to a real raster (bounds-only decode, no full allocation). Used to
     * reject streams that carry a JPEG marker but aren't decodable JPEGs — e.g. the R8's HDR-PQ
     * HEVC previews — so we never store a blank thumbnail.
     */
    private fun decodesToBitmap(bytes: ByteArray): Boolean {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            opts.outWidth > 0 && opts.outHeight > 0
        } catch (e: Throwable) {
            false
        }
    }

    fun toggleFileSelection(objectHandle: Int) {
        val current = _state.value
        if (current is TransferState.FilePicker) {
            val newSelected = if (objectHandle in current.selectedHandles) {
                current.selectedHandles - objectHandle
            } else {
                current.selectedHandles + objectHandle
            }
            _state.value = current.copy(selectedHandles = newSelected)
        }
    }

    fun selectAll() {
        val current = _state.value
        if (current is TransferState.FilePicker) {
            _state.value = current.copy(selectedHandles = current.files.map { it.objectHandle }.toSet())
        }
    }

    fun selectNone() {
        val current = _state.value
        if (current is TransferState.FilePicker) {
            _state.value = current.copy(selectedHandles = emptySet())
        }
    }

    fun toggleDeleteAfterTransfer() {
        val current = _state.value as? TransferState.FilePicker ?: return
        _state.value = current.copy(deleteAfterTransfer = !current.deleteAfterTransfer)
    }

    fun checkStorageAndProceed(): Boolean {
        val selected = selectedFiles()
        if (selected.isEmpty()) return false
        val requiredBytes = selected.sumOf { it.sizeBytes }
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val availableBytes = stat.availableBytes
        if (requiredBytes > availableBytes) {
            _state.value = TransferState.Error(
                message = "Not enough storage. Need ${formatSize(requiredBytes)}, available ${formatSize(availableBytes)}",
            )
            return false
        }
        return true
    }

    fun openHistory() {
        viewModelScope.launch {
            _sessionHistory.value = sessionRepository.loadSessions()
            _showHistory.value = true
        }
    }

    fun closeHistory() {
        _showHistory.value = false
    }

    fun saveCameraName(name: String) {
        val cameraId = deviceManager.cameraId
        viewModelScope.launch {
            deviceManager.setCameraName(name)
            val cameraName = deviceManager.cameraName
            if (!cameraId.isNullOrBlank() && !cameraName.isNullOrBlank()) {
                cameraNameRepository.saveCameraName(cameraId, cameraName)
            }
            scanCamera()
        }
    }

    fun updateState(newState: TransferState) {
        _state.value = newState
    }

    override fun onCleared() {
        deviceManager.close()
        super.onCleared()
    }

    private fun formatSize(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format("%.1f GB", gb)
        else String.format("%.0f MB", bytes / (1024.0 * 1024.0))
    }

    private companion object {
        // The embedded THMB/Exif preview lives in the CR3 header, well before the RAW payload.
        private const val CR3_HEAD_READ_BYTES = 512 * 1024
    }
}
