package com.canon.cr3transfer.ui.main

import android.hardware.usb.UsbDevice
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canon.cr3transfer.data.mtp.MtpDeviceManager
import com.canon.cr3transfer.data.mtp.MtpTransferRepository
import com.canon.cr3transfer.data.prefs.TransferSessionRepository
import com.canon.cr3transfer.domain.model.CameraFile
import com.canon.cr3transfer.domain.model.TransferSession
import com.canon.cr3transfer.domain.model.TransferState
import com.canon.cr3transfer.domain.usecase.ScanCameraUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val deviceManager: MtpDeviceManager,
    private val scanCameraUseCase: ScanCameraUseCase,
    private val transferRepository: MtpTransferRepository,
    private val sessionRepository: TransferSessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private val _thumbnails = MutableStateFlow<Map<Int, ByteArray>>(emptyMap())
    val thumbnails: StateFlow<Map<Int, ByteArray>> = _thumbnails.asStateFlow()

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
                _state.value = TransferState.CameraConnected
                scanCamera()
            } finally {
                isConnecting = false
            }
        }
    }

    fun onCameraDisconnected() {
        deviceManager.close()
        scannedFiles = emptyList()
        _thumbnails.value = emptyMap()
        _state.value = TransferState.Idle
    }

    fun scanCamera() {
        viewModelScope.launch {
            _state.value = TransferState.Scanning
            try {
                scannedFiles = scanCameraUseCase()
                if (scannedFiles.isEmpty()) {
                    _state.value = TransferState.Done(transferred = 0, skipped = 0, failed = 0)
                    return@launch
                }
                val newHandles = withContext(Dispatchers.IO) {
                    scannedFiles
                        .filter { !transferRepository.isAlreadyImported(it.name, it.fileType) }
                        .map { it.objectHandle }
                        .toSet()
                }
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
                _state.value = TransferState.Error(message = e.message ?: "Failed to scan camera")
            }
        }
    }

    private fun loadThumbnails(files: List<CameraFile>) {
        viewModelScope.launch {
            for (file in files) {
                val thumb = withContext(Dispatchers.IO) {
                    deviceManager.getThumbnail(file.objectHandle)
                }
                if (thumb != null) {
                    _thumbnails.value = _thumbnails.value + (file.objectHandle to thumb)
                }
            }
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
}
