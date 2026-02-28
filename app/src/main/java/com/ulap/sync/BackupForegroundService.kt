package com.ulap.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ulap.MainActivity
import com.ulap.R
import com.ulap.data.remote.CHUNK_MAX_RETRIES
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
        // Placeholder title; replaced in onStartCommand once the action is known.
        startForeground(NOTIFICATION_ID, buildProgressNotification(getString(R.string.notification_backup_title)))
        observeProgress()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BACKUP -> {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildProgressNotification("Starting backup…"))
                serviceScope.launch { syncEngine.startUpload() }
            }
            ACTION_START_RESTORE -> {
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildProgressNotification("Starting restore…", title = getString(R.string.notification_restore_title)))
                serviceScope.launch { syncEngine.startDownload() }
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
            syncEngine.progress.collectLatest { progress ->
                if (!progress.isActive) {
                    progress.completionEvent?.let { event ->
                        postCompletionNotification(event.succeeded, event.failed)
                        syncEngine.clearCompletionEvent()
                    }
                    stopSelf()
                    return@collectLatest
                }
                val text = when {
                    progress.isRateLimited ->
                        getString(R.string.notification_backup_rate_limited)
                    progress.chunkRetryAttempt > 0 ->
                        "Part ${progress.currentChunk}/${progress.totalChunks} — attempt ${progress.chunkRetryAttempt} of $CHUNK_MAX_RETRIES"
                    progress.totalChunks > 0 ->
                        "${progress.itemsDone + 1}/${progress.itemsTotal} · Part ${progress.currentChunk}/${progress.totalChunks}"
                    progress.itemsTotal > 0 ->
                        "${progress.itemsDone} of ${progress.itemsTotal} backed up"
                    else -> "Preparing…"
                }
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(
                    NOTIFICATION_ID,
                    buildProgressNotification(
                        text = text,
                        progressFraction = progress.currentFileFraction,
                        indeterminate = progress.itemsTotal == 0 || progress.isRateLimited,
                    )
                )
            }
        }
    }

    private fun postCompletionNotification(succeeded: Int, failed: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = if (failed == 0) {
            getString(R.string.notification_backup_done_body, succeeded)
        } else {
            getString(R.string.notification_backup_done_with_failures_body, succeeded, failed)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_COMPLETE_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(getString(R.string.notification_backup_done))
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
    ): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, (progressFraction * 100).toInt(), indeterminate)
            .build()

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

        fun cancel(context: Context) {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
