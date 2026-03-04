package com.ulap.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import com.ulap.data.local.dao.BackupFolderDao
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.domain.usecase.GetCredentialsUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lightweight background service that keeps a ContentObserver alive on the MediaStore
 * image and video URIs. When a change is detected (e.g. new photo taken), it debounces
 * for a short window then enqueues BackupWorker to scan + upload.
 *
 * Lifecycle:
 *  - Started whenever any folder has backup enabled (app-open or boot).
 *  - Stops itself when no folders remain enabled.
 */
@AndroidEntryPoint
class MediaObserverService : Service() {

    @Inject lateinit var folderDao: BackupFolderDao
    @Inject lateinit var getCredentials: GetCredentialsUseCase
    @Inject lateinit var userPrefs: UserPreferencesRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var debounceJob: Job? = null

    private val mediaObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onMediaStoreChanged()
        }
    }

    override fun onCreate() {
        super.onCreate()
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver,
        )
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Check if there are still enabled folders; if not, stop ourselves.
        serviceScope.launch {
            val hasEnabled = folderDao.getEnabled().isNotEmpty()
            if (!hasEnabled) stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(mediaObserver)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun onMediaStoreChanged() {
        // Debounce: camera writes can emit several MediaStore events per file.
        debounceJob?.cancel()
        debounceJob = serviceScope.launch {
            delay(DEBOUNCE_MS)
            if (!getCredentials.hasCredentials()) return@launch
            if (folderDao.getEnabled().isEmpty()) return@launch
            BackupWorker.enqueue(applicationContext, userPrefs)
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 3_000L

        fun start(context: Context) {
            context.startService(Intent(context, MediaObserverService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaObserverService::class.java))
        }
    }
}
