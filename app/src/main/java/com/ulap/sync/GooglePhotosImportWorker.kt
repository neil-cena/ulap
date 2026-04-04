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
import com.ulap.data.googlephotos.GooglePhotosApi
import com.ulap.data.googlephotos.GooglePhotosImportEntityFactory
import com.ulap.data.googlephotos.GooglePhotosImportItemStatus
import com.ulap.data.googlephotos.GooglePhotosImportManager
import com.ulap.data.googlephotos.formatGooglePhotosDiagnostics
import com.ulap.data.googlephotos.httpStatusCodeOrNull
import com.ulap.debug.DebugLogBuffer
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.repository.UserPreferencesRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
private const val TAG = "GooglePhotosImportWorker"
private const val NOTIFICATION_ID = 1003
private const val CHANNEL_ID = "ulap_google_photos_import"

@HiltWorker
class GooglePhotosImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val googlePhotosApi: GooglePhotosApi,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val mediaItemDao: MediaItemDao,
    private val importManager: GooglePhotosImportManager,
    private val googleAuthManager: GoogleAuthManager,
    private val debugLog: DebugLogBuffer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo())
        if (!googleAuthManager.refreshTokenFromLastAccount()) {
            debugLog.log(
                TAG,
                "No Google access token for Photos import (sign in again or check OAuth client / scopes)",
            )
            return Result.failure()
        }

        var nextPageToken: String? = userPreferencesRepository.googlePhotosPageToken.first()
        var imported = 0
        /** Items evaluated this run (uploaded, skipped duplicate, skipped unsupported, or failed attempt). */
        var processed = 0

        try {
            do {
                if (isStopped) break
                val response = googlePhotosApi.listMediaItems(pageSize = 100, pageToken = nextPageToken)
                val items = response.mediaItems.orEmpty()

                for (item in items) {
                    if (isStopped) break
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
                userPreferencesRepository.updateGooglePhotosPageToken(nextPageToken)
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
