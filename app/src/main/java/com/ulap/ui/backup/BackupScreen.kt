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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    val startBackup = rememberRunWithNotificationPermission(viewModel::startBackup)
    val retryFailed = rememberRunWithNotificationPermission(viewModel::retryFailed)

    LaunchedEffect(onOpenWithRetry) {
        if (onOpenWithRetry) {
            onConsumeRetry()
            // Wait for stats to load before retrying so we don't fire a no-op when
            // failed count is not yet known. A short timeout guards against a stall.
            withTimeoutOrNull(3_000) {
                viewModel.stats.first { it != null }
            }
            retryFailed()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                            // Show chunk position label for multi-chunk uploads (totalChunks == 1 cannot
                            // occur in practice: chunking requires >50MB, ceil(50MB/19MB) = 3 chunks min).
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
