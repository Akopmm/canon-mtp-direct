package com.canon.cr3transfer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.canon.cr3transfer.R
import com.canon.cr3transfer.domain.model.Cr3File
import com.canon.cr3transfer.domain.model.FileStatus
import com.canon.cr3transfer.domain.model.FileTransferStatus
import com.canon.cr3transfer.domain.model.TransferState
import com.canon.cr3transfer.domain.usecase.TransferFilesUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TransferForegroundService : Service() {

    @Inject lateinit var transferFilesUseCase: TransferFilesUseCase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val binder = LocalBinder()

    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    inner class LocalBinder : Binder() {
        val service: TransferForegroundService get() = this@TransferForegroundService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Preparing transfer…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    fun startTransfer(files: List<Cr3File>) {
        Log.d(TAG, "startTransfer called with ${files.size} files")
        scope.launch {
            try {
                Log.d(TAG, "Starting transfer flow collection")
                transferFilesUseCase(files)
                    .catch { e ->
                        Log.e(TAG, "Transfer flow error", e)
                        _transferState.value = TransferState.Error(
                            message = e.message ?: "Transfer failed",
                        )
                        stopSelf()
                    }
                    .collect { progress ->
                        Log.d(TAG, "Transfer progress: ${progress.completedFiles}/${progress.totalFiles} - ${progress.currentFileName}")
                        _transferState.value = TransferState.Transferring(
                            totalFiles = progress.totalFiles,
                            completedFiles = progress.completedFiles,
                            currentFileName = progress.currentFileName,
                            fileStatuses = progress.fileStatuses,
                        )
                        updateNotification(
                            "Transferring ${progress.completedFiles} of ${progress.totalFiles} — ${progress.currentFileName}"
                        )
                    }

                val current = _transferState.value
                if (current is TransferState.Transferring) {
                    val statuses = current.fileStatuses
                    _transferState.value = TransferState.Done(
                        transferred = statuses.count { it.status == FileStatus.DONE },
                        skipped = statuses.count { it.status == FileStatus.SKIPPED },
                        failed = statuses.count { it.status == FileStatus.ERROR },
                    )
                }
                Log.d(TAG, "Transfer completed, final state: ${_transferState.value}")
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "startTransfer coroutine failed", e)
                _transferState.value = TransferState.Error(message = e.message ?: "Transfer failed")
                stopSelf()
            }
        }
    }

    fun stopTransfer() {
        scope.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.transfer_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.transfer_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "TransferService"
        const val CHANNEL_ID = "transfer_channel"
        const val NOTIFICATION_ID = 1
    }
}
