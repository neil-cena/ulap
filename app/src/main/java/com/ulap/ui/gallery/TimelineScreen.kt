package com.ulap.ui.gallery

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ulap.R
import com.ulap.ui.mediaPermissions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.ulap.domain.model.BackupStatus
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.MediaType
import java.util.concurrent.TimeUnit

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TimelineScreen(
    onItemClick: (String) -> Unit,
    onSelectFolders: () -> Unit = {},
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val groups by viewModel.groups.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    val upToDateText = stringResource(R.string.up_to_date)
    LaunchedEffect(Unit) {
        try {
            viewModel.refreshCompleted.collect {
                snackbarHostState.showSnackbar(upToDateText)
            }
        } catch (_: Exception) {
            // SharedFlow collection should not throw in practice; guard defensively.
        }
    }

    var permissionGranted by remember {
        mutableStateOf(mediaPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permissionGranted = results.values.all { it }
        if (permissionGranted) viewModel.refresh()
    }

    val currentPermissionGranted by rememberUpdatedState(permissionGranted)
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = mediaPermissions().all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                if (granted && !currentPermissionGranted) {
                    permissionGranted = true
                    viewModel.refresh()
                } else {
                    permissionGranted = granted
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_timeline)) },
                actions = {
                    if (groups.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                viewModel.setViewMode(
                                    if (viewMode == TimelineViewMode.GRID) TimelineViewMode.LIST else TimelineViewMode.GRID
                                )
                            },
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(
                                imageVector = if (viewMode == TimelineViewMode.LIST) Icons.Default.ViewModule else Icons.AutoMirrored.Filled.List,
                                contentDescription = if (viewMode == TimelineViewMode.GRID)
                                    stringResource(R.string.content_desc_view_list)
                                else
                                    stringResource(R.string.content_desc_view_grid),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                !permissionGranted -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = stringResource(R.string.content_desc_permission_required),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(56.dp),
                            )
                            Text(
                                text = stringResource(R.string.permission_required_photos),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { permissionLauncher.launch(mediaPermissions()) }) {
                                Text(stringResource(R.string.grant_permission))
                            }
                        }
                    }
                }
                groups.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = stringResource(R.string.content_desc_no_backups),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(56.dp),
                            )
                            Text(
                                text = stringResource(R.string.timeline_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.timeline_empty_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = onSelectFolders,
                                enabled = !isRefreshing,
                            ) {
                                Text(stringResource(R.string.timeline_cta_select_folders))
                            }
                            OutlinedButton(
                                onClick = viewModel::refresh,
                                enabled = !isRefreshing,
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                Text(if (isRefreshing) stringResource(R.string.refreshing) else stringResource(R.string.sync_now))
                            }
                        }
                    }
                }
                else -> {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (viewMode == TimelineViewMode.LIST) {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                groups.forEach { group ->
                                    item(key = "header_${group.label}") {
                                        Text(
                                            text = group.label,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                        )
                                    }
                                    items(group.items, key = { it.id }) { item ->
                                        TimelineListRow(item = item, onClick = { onItemClick(item.id) })
                                    }
                                }
                            }
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                groups.forEach { group ->
                                    item(span = { GridItemSpan(3) }) {
                                        Text(
                                            text = group.label,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                        )
                                    }
                                    items(group.items, key = { it.id }) { item ->
                                        MediaThumbnail(item = item, onClick = { onItemClick(item.id) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineListRow(item: MediaItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(56.dp),
        ) {
            val imageModel = item.streamUrl ?: item.contentUri
            if (imageModel.isNotBlank()) {
                val painter = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
                AsyncImage(
                    model = if (item.streamUrl != null) imageModel else Uri.parse(imageModel),
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                    placeholder = painter,
                    error = painter,
                )
            } else {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = stringResource(R.string.content_desc_cloud_only),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (item.mediaType == MediaType.VIDEO) {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = stringResource(R.string.content_desc_video),
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(20.dp),
                )
            }
            BackupStatusIcon(
                status = item.backupStatus,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.fileName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun MediaThumbnail(item: MediaItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        val imageModel = item.streamUrl ?: item.contentUri
        if (imageModel.isNotBlank()) {
            val painter = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
            AsyncImage(
                model = if (item.streamUrl != null) imageModel else Uri.parse(imageModel),
                contentDescription = item.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                placeholder = painter,
                error = painter,
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = stringResource(R.string.content_desc_cloud_only),
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Video indicator
        if (item.mediaType == MediaType.VIDEO) {
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = stringResource(R.string.content_desc_video),
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp),
            )
            item.durationMs?.let { duration ->
                Text(
                    text = formatDuration(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color(0x99000000))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        // Backup status indicator
        BackupStatusIcon(
            status = item.backupStatus,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
        )
    }
}

@Composable
fun BackupStatusIcon(status: BackupStatus, modifier: Modifier = Modifier) {
    val icon = when (status) {
        BackupStatus.BACKED_UP -> Icons.Default.CheckCircle
        BackupStatus.CLOUD_ONLY -> Icons.Default.Cloud
        BackupStatus.FAILED -> Icons.Default.ErrorOutline
        BackupStatus.UPLOADING, BackupStatus.PENDING -> Icons.Default.HourglassBottom
        BackupStatus.EXCLUDED -> null
    }
    val tint = when (status) {
        BackupStatus.BACKED_UP -> MaterialTheme.colorScheme.tertiary
        BackupStatus.CLOUD_ONLY -> MaterialTheme.colorScheme.primary
        BackupStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> Color(0xFFFFFFFF)
    }
    val description = when (status) {
        BackupStatus.BACKED_UP -> stringResource(R.string.content_desc_backed_up)
        BackupStatus.CLOUD_ONLY -> stringResource(R.string.content_desc_cloud_only)
        BackupStatus.FAILED -> stringResource(R.string.content_desc_backup_failed)
        BackupStatus.UPLOADING -> stringResource(R.string.content_desc_uploading)
        BackupStatus.PENDING -> stringResource(R.string.content_desc_pending_backup)
        BackupStatus.EXCLUDED -> null
    }
    icon?.let {
        Icon(
            imageVector = it,
            contentDescription = description,
            tint = tint,
            modifier = modifier.size(16.dp),
        )
    }
}

private fun formatDuration(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
