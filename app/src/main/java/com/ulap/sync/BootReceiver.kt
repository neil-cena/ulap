package com.ulap.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ulap.data.local.dao.BackupFolderDao
import com.ulap.domain.usecase.GetCredentialsUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restarts MediaObserverService after device reboot if the user has credentials
 * and at least one folder with backup enabled.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var getCredentials: GetCredentialsUseCase
    @Inject lateinit var folderDao: BackupFolderDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!getCredentials.hasCredentials()) return
        scope.launch {
            if (folderDao.getEnabled().isNotEmpty()) {
                MediaObserverService.start(context)
            }
        }
    }
}
