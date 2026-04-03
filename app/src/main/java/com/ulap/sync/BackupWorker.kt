package com.ulap.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.ScanMediaUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncEngine: SyncEngine,
    private val getCredentials: GetCredentialsUseCase,
    private val scanMedia: ScanMediaUseCase,
    private val userPrefs: UserPreferencesRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (!getCredentials.hasCredentials()) return Result.success()
        return try {
            // Full scan reconciles the DB with MediaStore so locally deleted files are removed
            // from the backup queue (see MediaRepositoryImpl.scanAndSync effectiveFullScan).
            scanMedia(fullScan = true)
            syncEngine.startUpload()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "ulap_auto_backup"

        fun enqueue(context: Context, userPrefs: UserPreferencesRepository? = null) {
            val networkType = if (userPrefs?.wifiOnly?.value == true) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            }
            val requiresBatteryNotLow = userPrefs?.pauseOnLowBattery?.value == true

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresBatteryNotLow(requiresBatteryNotLow)
                .build()

            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(constraints)
                .build()

            // REPLACE: always enqueue a fresh job. KEEP would silently skip if a previous
            // job already completed (SUCCEEDED state), causing subsequent photos to be missed.
            // REPLACE on a running job cancels and re-enqueues, which is safe because
            // scanMedia + startUpload will pick up all pending items on the next run.
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
