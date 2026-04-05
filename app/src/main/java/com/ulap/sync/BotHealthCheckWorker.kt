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
import com.ulap.domain.health.BotHealthMonitor
import com.ulap.domain.usecase.GetCredentialsUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job that checks all configured bots for ban/health status every 6 hours.
 *
 * If a bot is found to be banned, [BotHealthMonitor] records the ban persistently via
 * [com.ulap.data.remote.BotBanStore] and updates the health state flow observed by the UI.
 */
@HiltWorker
class BotHealthCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val getCredentials: GetCredentialsUseCase,
    private val botHealthMonitor: BotHealthMonitor,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (!getCredentials.hasCredentials()) return Result.success()
        botHealthMonitor.checkAll()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "ulap_bot_health_check"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<BotHealthCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
