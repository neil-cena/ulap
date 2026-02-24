package com.ulap.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
private const val CHANNEL_ID = "ulap_sync"

@AndroidEntryPoint
class BackupForegroundService : Service() {

    @Inject lateinit var syncEngine: SyncEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // indeterminate = true (default), progressFraction = 0f (default) — correct for startup
        startForeground(NOTIFICATION_ID, buildNotification("Starting backup…"))
        observeProgress()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BACKUP -> serviceScope.launch { syncEngine.startUpload() }
            ACTION_START_RESTORE -> serviceScope.launch { syncEngine.startDownload() }
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
                    stopSelf()
                    return@collectLatest
                }
                val text = when {
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
                    buildNotification(
                        text = text,
                        progressFraction = progress.currentFileFraction,
                        // indeterminate only during "Preparing…" — not when progressFraction == 0f,
                        // which would re-trigger the spinner at the start of each file and on resume start.
                        indeterminate = progress.itemsTotal == 0,
                    )
                )
            }
        }
    }

    private fun buildNotification(
        text: String,
        progressFraction: Float = 0f,
        indeterminate: Boolean = true,
    ): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(getString(R.string.notification_backup_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, (progressFraction * 100).toInt(), indeterminate)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_backup),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
