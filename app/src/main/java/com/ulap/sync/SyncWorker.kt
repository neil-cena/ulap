package com.ulap.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ulap.domain.usecase.FetchIndexFromPinnedMessageUseCase
import com.ulap.domain.usecase.GetCredentialsUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val getCredentials: GetCredentialsUseCase,
    private val fetchIndex: FetchIndexFromPinnedMessageUseCase,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (!getCredentials.hasCredentials()) return Result.success()
        return fetchIndex().fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }

    companion object {
        private const val WORK_NAME = "ulap_periodic_sync"

        fun schedule(context: Context, wifiOnly: Boolean = false, pauseOnLowBattery: Boolean = false) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(pauseOnLowBattery)
                .build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
        }
    }
}
