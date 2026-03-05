package com.canon.cr3transfer.ui.main

import android.hardware.usb.UsbDevice
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.canon.cr3transfer.data.mtp.MtpDeviceManager
import com.canon.cr3transfer.domain.model.Cr3File
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
) : ViewModel() {

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    private var scannedFiles: List<Cr3File> = emptyList()
    private var isConnecting = false

    val files: List<Cr3File> get() = scannedFiles

    fun onCameraConnected(usbDevice: UsbDevice) {
        val currentState = _state.value
        if (isConnecting) {
            android.util.Log.d("CR3Transfer", "onCameraConnected: skipping, already connecting")
            return
        }
        if (deviceManager.isConnected &&
            (currentState is TransferState.CameraConnected ||
             currentState is TransferState.Scanning ||
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
                val requiredBytes = scannedFiles.sumOf { it.sizeBytes }
                val stat = StatFs(Environment.getExternalStorageDirectory().path)
                val availableBytes = stat.availableBytes
                if (requiredBytes > availableBytes) {
                    _state.value = TransferState.Error(
                        message = "Not enough storage. Need ${formatSize(requiredBytes)}, available ${formatSize(availableBytes)}",
                    )
                    return@launch
                }
                _state.value = TransferState.CameraConnected
            } catch (e: Exception) {
                _state.value = TransferState.Error(
                    message = e.message ?: "Failed to scan camera",
                )
            }
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
        return if (gb >= 1.0) {
            String.format("%.1f GB", gb)
        } else {
            String.format("%.0f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
