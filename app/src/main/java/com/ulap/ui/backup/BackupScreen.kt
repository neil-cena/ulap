package com.ulap.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ulap.R
import com.ulap.data.remote.ThrottleReason
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.FileUploadProgress
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
    val mobileDataWarning by viewModel.mobileDataWarning.collectAsState()
    val failedItems by viewModel.failedItems.collectAsState()

    val startBackup = rememberRunWithNotificationPermission(viewModel::startBackup)
    val retryFailed = rememberRunWithNotificationPermission(viewModel::retryFailed)

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
                }
            }
        }

        // Failed files card
        if (failedItems.isNotEmpty() && !progress.isActive) {
            Spacer(Modifier.height(12.dp))
            FailedFilesCard(failedItems)
        }

        Spacer(Modifier.height(16.dp))

        if (progress.isActive && progress.operation == SyncOperation.UPLOADING) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (progress.isRateLimited) {
                        ThrottleBanner(
                            reason = progress.throttleReason,
                            resumeAtMs = progress.throttleResumeAtMs,
                        )
                    } else if (progress.itemsTotal == 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.backup_preparing),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.backup_progress_uploading, progress.itemsDone + 1, progress.itemsTotal),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val uploads = progress.activeUploads.values
                        val largeUpload = uploads.firstOrNull { it.isChunked }
                        // For the small panel, pick whichever non-chunked file has made the most
                        // progress — a good proxy for "the one currently sending bytes".
                        val smallUpload = uploads
                            .filter { !it.isChunked }
                            .maxByOrNull { it.bytesUploaded }
                        if (largeUpload != null || smallUpload != null) {
                            Spacer(Modifier.height(10.dp))
                            if (largeUpload != null) {
                                LargeUploadPanel(largeUpload)
                            }
                            if (largeUpload != null && smallUpload != null) {
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(Modifier.height(10.dp))
                            }
                            if (smallUpload != null) {
                                SmallUploadPanel(smallUpload)
                            }
                        } else {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { progress.progressFraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        // Subtle indicator when uploads are active but running at reduced speed.
                        if (progress.throttleReason == ThrottleReason.ADAPTIVE_SLOWDOWN) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.throttle_reduced_speed),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/**
 * Rich throttle banner that clearly communicates *why* uploads are slow and reassures the user.
 * Shows a live countdown when the system knows when normal speed will resume.
 */
@Composable
private fun ThrottleBanner(reason: ThrottleReason, resumeAtMs: Long) {
    val primaryText = stringResource(
        when (reason) {
            ThrottleReason.CIRCUIT_OPEN -> R.string.throttle_circuit_open
            ThrottleReason.BUDGET_LIMIT -> R.string.throttle_budget_limit
            else -> R.string.throttle_adaptive
        }
    )

    // Live countdown: tick every second when we know the resume time.
    var remainingMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(resumeAtMs) {
        if (resumeAtMs > 0) {
            while (true) {
                remainingMs = (resumeAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
                if (remainingMs == 0L) break
                delay(1_000)
            }
        } else {
            remainingMs = 0L
        }
    }

    Column {
        Text(
            primaryText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (remainingMs > 0) {
            Spacer(Modifier.height(2.dp))
            val mins = remainingMs / 60_000L
            val secs = (remainingMs % 60_000L) / 1_000L
            val countdownText = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
            Text(
                stringResource(R.string.throttle_resuming_in, countdownText),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.throttle_reassurance),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FailedFilesCard(items: List<MediaItem>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.failed_files_title, items.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.12f),
                    )
                } else {
                    Spacer(Modifier.height(10.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        item.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Text(
                        item.errorMessage?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.failed_files_reason_default),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Large-file panel: filename, "x MB / y GB", full-width progress bar. No chunk counter. */
@Composable
private fun LargeUploadPanel(upload: FileUploadProgress) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                upload.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            if (upload.bytesTotal > 0) {
                Text(
                    "${formatBytes(upload.bytesUploaded)} / ${formatBytes(upload.bytesTotal)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { upload.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Small-file panel: same two-row layout, slightly muted — represents the "current" small worker. */
@Composable
private fun SmallUploadPanel(upload: FileUploadProgress) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                upload.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            if (upload.bytesTotal > 0) {
                Text(
                    "${formatBytes(upload.bytesUploaded)} / ${formatBytes(upload.bytesTotal)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { upload.fraction },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}
