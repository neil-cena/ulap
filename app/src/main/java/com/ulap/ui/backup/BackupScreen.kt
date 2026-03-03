package com.ulap.ui.backup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ulap.R
import com.ulap.domain.model.SyncOperation
import com.ulap.ui.rememberRunWithNotificationPermission

@Composable
fun BackupScreen(
    onOpenWithRetry: Boolean = false,
    onConsumeRetry: () -> Unit = {},
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val freeSpace by viewModel.freeSpace.collectAsState()
    val mobileDataWarning by viewModel.mobileDataWarning.collectAsState()

    val startBackup = rememberRunWithNotificationPermission(viewModel::startBackup)
    val retryFailed = rememberRunWithNotificationPermission(viewModel::retryFailed)

    var showFreeSpaceConfirm by remember { mutableStateOf(false) }

    // Launcher for MediaStore.createDeleteRequest (Android 11+)
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onDeleteGranted()
        } else {
            viewModel.dismissFreeUpSpace()
        }
    }

    // When the ViewModel produces a delete sender, launch it immediately.
    LaunchedEffect(freeSpace.deleteSender) {
        val sender = freeSpace.deleteSender ?: return@LaunchedEffect
        viewModel.consumeDeleteSender()
        deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    LaunchedEffect(onOpenWithRetry) {
        if (onOpenWithRetry) {
            onConsumeRetry()
            withTimeoutOrNull(3_000) {
                viewModel.stats.first { it != null }
            }
            retryFailed()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.nav_backup), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        stats?.let { s ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val statusText = if (s.cloudOnly > 0) {
                        stringResource(R.string.backup_stats_local_cloud_total, s.backedUp, s.cloudOnly, s.total)
                    } else {
                        stringResource(R.string.backup_stats_backed_up_total, s.backedUp, s.total)
                    }
                    Text(statusText, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        StatChip(stringResource(R.string.backup_stat_pending, s.pending))
                        Spacer(Modifier.width(8.dp))
                        StatChip(stringResource(R.string.backup_stat_failed, s.failed))
                        if (s.cloudOnly > 0) {
                            Spacer(Modifier.width(8.dp))
                            StatChip(stringResource(R.string.backup_stat_cloud, s.cloudOnly))
                        }
                    }
                    if (s.excluded > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.backup_stat_excluded_hint, s.excluded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Free up space card — show when there are locally-stored backed-up items and no active upload
        val showFreeUp = (stats?.backedUp ?: 0) > 0 && !progress.isActive
        if (showFreeUp) {
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.free_space_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.free_space_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.prepareFreeUpSpace()
                            showFreeSpaceConfirm = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !freeSpace.isLoading,
                    ) {
                        Text(if (freeSpace.isLoading) stringResource(R.string.free_space_loading) else stringResource(R.string.free_space_action))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (progress.isActive && progress.operation == SyncOperation.UPLOADING) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (progress.isRateLimited) {
                        Text(
                            stringResource(R.string.backup_rate_limited_ui),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(
                            stringResource(R.string.backup_progress_uploading, progress.itemsDone + 1, progress.itemsTotal),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (progress.currentFileName.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                progress.currentFileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { progress.currentFileFraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (progress.currentFileBytesTotal > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${formatBytes(progress.currentFileBytes)} / ${formatBytes(progress.currentFileBytesTotal)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (progress.totalChunks > 1) {
                                Spacer(Modifier.height(4.dp))
                                val chunkLabel = if (progress.chunkRetryAttempt > 0)
                                    stringResource(R.string.backup_chunk_retry, progress.currentChunk, progress.totalChunks)
                                else
                                    stringResource(R.string.backup_chunk_progress, progress.currentChunk, progress.totalChunks)
                                Text(
                                    chunkLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { progress.progressFraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = viewModel::pauseBackup,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.pause)) }
                }
            }
        } else if (progress.isPaused) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.backup_paused),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::resumeBackup,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.resume)) }
                }
            }
        } else {
            Row {
                Button(
                    onClick = startBackup,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.back_up_now)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = viewModel::syncNow,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.sync_now)) }
                if ((stats?.failed ?: 0) > 0) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = retryFailed,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.retry_failed)) }
                }
            }
        }
    }

    if (mobileDataWarning) {
        AlertDialog(
            onDismissRequest = viewModel::dismissMobileDataWarning,
            title = { Text(stringResource(R.string.mobile_data_warning_title)) },
            text = { Text(stringResource(R.string.mobile_data_warning_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmBackupOnMobileData) {
                    Text(stringResource(R.string.mobile_data_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissMobileDataWarning) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Confirmation dialog before triggering the system delete picker
    if (showFreeSpaceConfirm && !freeSpace.isLoading && freeSpace.items.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                showFreeSpaceConfirm = false
                viewModel.dismissFreeUpSpace()
            },
            title = { Text(stringResource(R.string.free_space_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.free_space_confirm_body,
                        formatBytes(freeSpace.totalBytes),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showFreeSpaceConfirm = false
                    if (freeSpace.deleteSender != null) {
                        // deleteSender already set — LaunchedEffect will launch it
                    } else {
                        // Pre-API-30: no system dialog; mark immediately
                        viewModel.onDeleteGranted()
                    }
                }) { Text(stringResource(R.string.free_space_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFreeSpaceConfirm = false
                    viewModel.dismissFreeUpSpace()
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

@Composable
private fun StatChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
