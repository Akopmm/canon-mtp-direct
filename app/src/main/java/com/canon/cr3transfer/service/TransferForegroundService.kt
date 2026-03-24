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
import com.canon.cr3transfer.data.prefs.TransferSessionRepository
import com.canon.cr3transfer.domain.model.CameraFile
import com.canon.cr3transfer.domain.model.FileStatus
import com.canon.cr3transfer.domain.model.TransferSession
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
    @Inject lateinit var sessionRepository: TransferSessionRepository

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
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildProgressNotification("Preparing transfer…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    fun startTransfer(files: List<CameraFile>, deleteAfterTransfer: Boolean = false) {
        Log.d(TAG, "startTransfer called with ${files.size} files, deleteAfterTransfer=$deleteAfterTransfer")
        val startTimeMs = System.currentTimeMillis()
        scope.launch {
            try {
                transferFilesUseCase(files, deleteAfterTransfer)
                    .catch { e ->
                        Log.e(TAG, "Transfer flow error", e)
                        _transferState.value = TransferState.Error(message = e.message ?: "Transfer failed")
                        stopSelf()
                    }
                    .collect { progress ->
                        Log.d(TAG, "Progress: ${progress.completedFiles}/${progress.totalFiles} — ${progress.currentFileName}")
                        _transferState.value = TransferState.Transferring(
                            totalFiles = progress.totalFiles,
                            completedFiles = progress.completedFiles,
                            currentFileName = progress.currentFileName,
                            fileStatuses = progress.fileStatuses,
                            transferSpeedMbps = progress.speedMbps,
                        )
                        updateProgressNotification(
                            "Transferring ${progress.completedFiles} of ${progress.totalFiles} — ${progress.currentFileName}"
                        )
                    }

                val current = _transferState.value
                if (current is TransferState.Transferring) {
                    val statuses = current.fileStatuses
                    val done = TransferState.Done(
                        transferred = statuses.count { it.status == FileStatus.DONE },
                        skipped = statuses.count { it.status == FileStatus.SKIPPED },
                        failed = statuses.count { it.status == FileStatus.ERROR },
                    )
                    _transferState.value = done

                    // Save session history
                    val durationMs = System.currentTimeMillis() - startTimeMs
                    val totalBytes = files.zip(statuses)
                        .filter { (_, s) -> s.status == FileStatus.DONE }
                        .sumOf { (f, _) -> f.sizeBytes }
                    scope.launch(Dispatchers.IO) {
                        sessionRepository.saveSession(
                            TransferSession(
                                id = startTimeMs.toString(),
                                dateMillis = startTimeMs,
                                transferred = done.transferred,
                                skipped = done.skipped,
                                failed = done.failed,
                                totalBytes = totalBytes,
                                durationMs = durationMs,
                            )
                        )
                    }

                    // Post completion notification
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    postCompletionNotification(done.transferred, done.skipped, done.failed)
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

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.transfer_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(COMPLETION_CHANNEL_ID, "Transfer Complete", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun buildProgressNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.transfer_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    private fun updateProgressNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildProgressNotification(text))
    }

    private fun postCompletionNotification(transferred: Int, skipped: Int, failed: Int) {
        val text = "$transferred transferred, $skipped skipped" + if (failed > 0) ", $failed failed" else ""
        val notification = NotificationCompat.Builder(this, COMPLETION_CHANNEL_ID)
            .setContentTitle("Canon Transfer Complete")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "TransferService"
        const val CHANNEL_ID = "transfer_channel"
        const val COMPLETION_CHANNEL_ID = "transfer_complete_channel"
        const val NOTIFICATION_ID = 1
        const val COMPLETION_NOTIFICATION_ID = 2
    }
}
