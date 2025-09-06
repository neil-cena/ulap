package com.ulap.ui.gallery // date headers

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.ulap.domain.model.BackupStatus
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.MediaType
import java.util.concurrent.TimeUnit

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

@Composable
fun TimelineScreen(
    onItemClick: (String) -> Unit,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val groups by viewModel.groups.collectAsState()

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
    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            permissionLauncher.launch(mediaPermissions())
        }
    }

    if (groups.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("No media found. Select folders in Settings to start backup.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        groups.forEach { group ->
            item(span = { GridItemSpan(3) }) {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(group.items, key = { it.id }) { item ->
                MediaThumbnail(item = item, onClick = { onItemClick(item.id) })
            }
        }
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
            AsyncImage(
                model = if (item.streamUrl != null) imageModel else Uri.parse(imageModel),
                contentDescription = item.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
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
                    contentDescription = "Cloud",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Video indicator
        if (item.mediaType == MediaType.VIDEO) {
            Icon(
                imageVector = Icons.Default.PlayCircle,
                contentDescription = "Video",
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
        BackupStatus.BACKED_UP -> Color(0xFF4CAF50)
        BackupStatus.CLOUD_ONLY -> Color(0xFF2196F3)
        BackupStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> Color(0xFFFFFFFF)
    }
    icon?.let {
        Icon(
            imageVector = it,
            contentDescription = status.name,
            tint = tint,
            modifier = modifier.size(16.dp),
        )
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(minutes, seconds)
}
