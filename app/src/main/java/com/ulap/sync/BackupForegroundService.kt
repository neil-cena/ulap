package com.ulap.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ulap.MainActivity
import com.ulap.R
import com.ulap.data.remote.CHUNK_MAX_RETRIES
import com.ulap.data.remote.ThrottleReason
import com.ulap.domain.model.SyncOperation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val NOTIFICATION_ID = 1001
private const val COMPLETION_NOTIFICATION_ID = 1002
private const val CHANNEL_ID = "ulap_sync"
private const val CHANNEL_COMPLETE_ID = "ulap_complete"

@AndroidEntryPoint
class BackupForegroundService : Service() {

    @Inject lateinit var syncEngine: SyncEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        val notification = buildProgressNotification(getString(R.string.notification_backup_title))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        observeProgress()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BACKUP -> {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildProgressNotification(getString(R.string.backup_starting)))
                serviceScope.launch { syncEngine.startUpload() }
            }
            ACTION_START_RESTORE -> {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildProgressNotification(getString(R.string.restore_starting), title = getString(R.string.notification_restore_title)))
                serviceScope.launch { syncEngine.startDownload() }
            }
            ACTION_PAUSE -> {
                syncEngine.pause()
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildPausedNotification())
            }
            ACTION_RESUME -> {
                serviceScope.launch { syncEngine.resume() }
            }
            ACTION_CANCEL -> {
                syncEngine.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun observeProgress() {
        serviceScope.launch {
            var observedActiveRun = false
            // Seed from current value so the completion path knows the operation type even
            // if an idle emission fires before any active emission is observed (race-safe).
            var lastOperation = syncEngine.progress.value.operation
            syncEngine.progress.collectLatest { progress ->
                if (progress.isActive) {
                    observedActiveRun = true
                    lastOperation = progress.operation
                }
                if (!progress.isActive) {
                    // While paused, keep the service alive with a paused notification.
                    if (progress.isPaused) {
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIFICATION_ID, buildPausedNotification())
                        return@collectLatest
                    }
                    // Ignore the initial idle emission before startUpload/startDownload updates progress.
                    if (!observedActiveRun) return@collectLatest
                    progress.completionEvent?.let { event ->
                        postCompletionNotification(event.succeeded, event.failed, isRestore = lastOperation == SyncOperation.DOWNLOADING)
                        syncEngine.clearCompletionEvent()
                    }
                    stopSelf()
                    return@collectLatest
                }
                val isRestore = progress.operation == SyncOperation.DOWNLOADING
                val text = when {
                    progress.isRateLimited -> when (progress.throttleReason) {
                        ThrottleReason.CIRCUIT_OPEN -> {
                            val remainingMs = (progress.throttleResumeAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
                            if (remainingMs > 0) {
                                val mins = remainingMs / 60_000L
                                val secs = (remainingMs % 60_000L) / 1_000L
                                val eta = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
                                getString(R.string.notification_throttle_circuit_open, eta)
                            } else getString(R.string.notification_backup_rate_limited)
                        }
                        ThrottleReason.BUDGET_LIMIT -> {
                            val remainingMs = (progress.throttleResumeAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
                            if (remainingMs > 0) {
                                val mins = remainingMs / 60_000L
                                val secs = (remainingMs % 60_000L) / 1_000L
                                val eta = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
                                getString(R.string.notification_throttle_budget, eta)
                            } else getString(R.string.notification_backup_rate_limited)
                        }
                        ThrottleReason.ADAPTIVE_SLOWDOWN ->
                            getString(R.string.notification_throttle_adaptive)
                        else ->
                            getString(R.string.notification_backup_rate_limited)
                    }
                    progress.chunkRetryAttempt > 0 ->
                        getString(R.string.backup_chunk_retry_notification, progress.currentChunk, progress.totalChunks, progress.chunkRetryAttempt, CHUNK_MAX_RETRIES)
                    progress.totalChunks > 50 && !isRestore -> {
                        // Large file: show chunk progress, size, and ETA.
                        val chunkText = getString(R.string.backup_chunk_progress, progress.currentChunk, progress.totalChunks)
                        val uploadedMb = progress.currentFileBytes / (1024 * 1024)
                        val totalMb = progress.currentFileBytesTotal / (1024 * 1024)
                        val sizeText = if (totalMb > 0) " ($uploadedMb MB / $totalMb MB)" else ""
                        val etaText = if (progress.estimatedRemainingMs > 60_000L) {
                            val mins = progress.estimatedRemainingMs / 60_000L
                            " · ~${mins}m"
                        } else ""
                        "${progress.itemsDone + 1}/${progress.itemsTotal} · $chunkText$sizeText$etaText"
                    }
                    progress.totalChunks > 0 && !isRestore ->
                        getString(R.string.backup_chunk_progress, progress.currentChunk, progress.totalChunks).let {
                            "${progress.itemsDone + 1}/${progress.itemsTotal} · $it"
                        }
                    progress.itemsTotal > 0 && isRestore ->
                        getString(R.string.backup_progress_restoring, progress.itemsDone, progress.itemsTotal)
                    progress.itemsTotal > 0 ->
                        getString(R.string.backup_progress_backed_up, progress.itemsDone, progress.itemsTotal)
                    else -> getString(R.string.backup_preparing)
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(
                    NOTIFICATION_ID,
                    buildProgressNotification(
                        text = text,
                        progressFraction = progress.currentFileFraction,
                        indeterminate = progress.itemsTotal == 0 || progress.isRateLimited,
                        showPause = !isRestore,
                    )
                )
            }
        }
    }

    private fun postCompletionNotification(succeeded: Int, failed: Int, isRestore: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!isRestore && failed > 0) {
                putExtra(MainActivity.EXTRA_OPEN_BACKUP_RETRY, true)
            }
        }
        // Use distinct request codes so backup and restore PendingIntents don't overwrite each other.
        val requestCode = if (isRestore) 2 else 1
        val tapPending = PendingIntent.getActivity(
            this, requestCode, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, body) = if (isRestore) {
            val bodyText = if (failed == 0) {
                resources.getQuantityString(R.plurals.notification_restore_done_body, succeeded, succeeded)
            } else {
                getString(R.string.notification_restore_done_with_failures_body, succeeded, failed)
            }
            getString(R.string.notification_restore_done) to bodyText
        } else {
            val bodyText = if (failed == 0) {
                resources.getQuantityString(R.plurals.notification_backup_done_body, succeeded, succeeded)
            } else {
                getString(R.string.notification_backup_done_with_failures_body, succeeded, failed)
            }
            getString(R.string.notification_backup_done) to bodyText
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_COMPLETE_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(tapPending)
            .build()
        nm.notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    private fun buildProgressNotification(
        text: String,
        progressFraction: Float = 0f,
        indeterminate: Boolean = true,
        title: String = getString(R.string.notification_backup_title),
        showPause: Boolean = false,
    ): Notification {
        val pauseIntent = PendingIntent.getService(
            this, 10,
            Intent(this, BackupForegroundService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, (progressFraction * 100).toInt(), indeterminate)
            .apply {
                if (showPause) addAction(0, getString(R.string.pause), pauseIntent)
            }
            .build()
    }

    private fun buildPausedNotification(): Notification {
        val resumeIntent = PendingIntent.getService(
            this, 11,
            Intent(this, BackupForegroundService::class.java).apply { action = ACTION_RESUME },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_backup_title))
            .setContentText(getString(R.string.backup_paused))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.resume), resumeIntent)
            .build()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_backup), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COMPLETE_ID, getString(R.string.notification_channel_complete), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_BACKUP = "com.ulap.action.START_BACKUP"
        const val ACTION_START_RESTORE = "com.ulap.action.START_RESTORE"
        const val ACTION_PAUSE = "com.ulap.action.PAUSE"
        const val ACTION_RESUME = "com.ulap.action.RESUME"
        const val ACTION_CANCEL = "com.ulap.action.CANCEL"

        fun startBackup(context: Context) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_START_BACKUP
            }
            context.startForegroundService(intent)
        }

        fun startRestore(context: Context) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_START_RESTORE
            }
            context.startForegroundService(intent)
        }

        fun pause(context: Context) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resume(context: Context) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
