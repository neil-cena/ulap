package com.ulap.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ulap.MainActivity
import com.ulap.R
import com.ulap.data.auth.GoogleAuthManager
import com.ulap.data.googlephotos.GooglePhotosImportEntityFactory
import com.ulap.data.googlephotos.GooglePhotosImportItemStatus
import com.ulap.data.googlephotos.GooglePhotosImportManager
import com.ulap.data.googlephotos.GooglePhotosPickerApi
import com.ulap.data.googlephotos.formatGooglePhotosDiagnostics
import com.ulap.data.googlephotos.httpStatusCodeOrNull
import com.ulap.data.googlephotos.toGooglePhotosMediaItem
import com.ulap.debug.DebugLogBuffer
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.remote.BackupIndexManager
import com.ulap.domain.repository.CredentialRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "GooglePhotosImportWorker"
private const val NOTIFICATION_ID = 1003
private const val CHANNEL_ID = "ulap_google_photos_import"

@HiltWorker
class GooglePhotosImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val pickerApi: GooglePhotosPickerApi,
    private val mediaItemDao: MediaItemDao,
    private val importManager: GooglePhotosImportManager,
    private val googleAuthManager: GoogleAuthManager,
    private val backupIndexManager: BackupIndexManager,
    private val credentialRepository: CredentialRepository,
    private val debugLog: DebugLogBuffer,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_SESSION_ID = "picker_session_id"
        private val EMPTY_JSON_BODY = "{}".toRequestBody("application/json".toMediaType())
    }

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())

        val sessionId = inputData.getString(KEY_SESSION_ID)
        if (sessionId.isNullOrBlank()) {
            debugLog.log(TAG, "No picker session ID provided — cannot start import")
            return Result.failure()
        }

        if (!googleAuthManager.refreshTokenFromLastAccount()) {
            debugLog.log(
                TAG,
                "No Google access token for Photos import (sign in again or check OAuth client / scopes)",
            )
            return Result.failure()
        }

        var nextPageToken: String? = null
        var imported = 0
        var processed = 0

        try {
            do {
                if (isStopped) break
                val response = pickerApi.listMediaItems(
                    sessionId = sessionId,
                    pageSize = 100,
                    pageToken = nextPageToken,
                )
                val items = response.mediaItems.orEmpty()

                for (pickedItem in items) {
                    if (isStopped) break
                    val item = pickedItem.toGooglePhotosMediaItem()
                    try {
                        val result = importManager.importGooglePhotosMediaItem(item)
                        result.fold(
                            onSuccess = { status ->
                                processed++
                                when (status) {
                                    GooglePhotosImportItemStatus.UPLOADED -> imported++
                                    GooglePhotosImportItemStatus.SKIPPED_DUPLICATE,
                                    GooglePhotosImportItemStatus.SKIPPED_UNSUPPORTED,
                                    -> Unit
                                }
                            },
                            onFailure = { err ->
                                processed++
                                mediaItemDao.upsert(
                                    GooglePhotosImportEntityFactory.failedEntity(
                                        item,
                                        err.message ?: "import failed",
                                    ),
                                )
                            },
                        )
                    } catch (e: Exception) {
                        debugLog.log(
                            TAG,
                            "item import failed id=${item.id}: ${formatGooglePhotosDiagnostics(e)}",
                        )
                        processed++
                        try {
                            mediaItemDao.upsert(
                                GooglePhotosImportEntityFactory.failedEntity(
                                    item,
                                    e.message ?: e.javaClass.simpleName,
                                ),
                            )
                        } catch (db: Exception) {
                            debugLog.log(TAG, "failed to persist FAILED row: ${formatGooglePhotosDiagnostics(db)}")
                        }
                    }
                    setProgress(
                        workDataOf(
                            "progress" to imported,
                            "total" to processed,
                        ),
                    )
                }

                nextPageToken = response.nextPageToken
            } while (nextPageToken != null && !isStopped)
        } catch (e: Exception) {
            debugLog.log(TAG, "import loop failed: ${formatGooglePhotosDiagnostics(e)}")
            when (e.httpStatusCodeOrNull()) {
                401, 403 -> {
                    googleAuthManager.clearAccessToken()
                    return Result.failure()
                }
                else -> {
                    googleAuthManager.refreshTokenFromLastAccount()
                    return Result.retry()
                }
            }
        }

        // Clean up the session after a successful (or fully-stopped) run
        if (!isStopped) {
            runCatching { pickerApi.deleteSession(sessionId) }
                .onFailure { debugLog.log(TAG, "deleteSession failed (non-critical): ${it.message}") }

            val token = credentialRepository.getBotToken()
            val chatId = credentialRepository.getChatId()
            if (token != null && chatId != null) {
                runCatching { backupIndexManager.exportAndUpload(token, chatId) }
                    .onFailure { debugLog.log(TAG, "index export failed (non-critical): ${it.message}") }
                    .onSuccess { result ->
                        result.onFailure { debugLog.log(TAG, "index export returned failure (non-critical): ${it.message}") }
                    }
            } else {
                debugLog.log(TAG, "index export skipped — no Telegram credentials configured")
            }
        }

        return Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        ensureChannel()
        val tapIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            android.content.Intent(applicationContext, MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.google_photos_import_notification_title))
            .setContentText(applicationContext.getString(R.string.google_photos_import_notification_body))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.google_photos_import_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
