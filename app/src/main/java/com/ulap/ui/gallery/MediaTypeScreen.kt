package com.ulap.ui.gallery

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ulap.R
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.MediaType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaTypeScreen(
    onItemClick: (String) -> Unit,
    viewModel: MediaTypeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val groups by viewModel.groups.collectAsState()
    val selectedType by viewModel.selectedType.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuItem by remember { mutableStateOf<MediaItem?>(null) }
    var infoItem by remember { mutableStateOf<MediaItem?>(null) }
    val removeConfirm by viewModel.removeFromDeviceConfirmation.collectAsState()
    val downloadingIds by viewModel.downloadingIds.collectAsState()

    val deleteFromDeviceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onRemoveFromDeviceConfirmed()
        } else {
            viewModel.dismissRemoveFromDeviceConfirmation()
        }
    }
    LaunchedEffect(removeConfirm.deleteSender) {
        val sender = removeConfirm.deleteSender ?: return@LaunchedEffect
        viewModel.consumeRemoveFromDeviceDeleteSender()
        deleteFromDeviceLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    LaunchedEffect(Unit) {
        try {
            viewModel.snackbarMessages.collect { snackbarHostState.showSnackbar(it) }
        } catch (_: Exception) {
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_media_type)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedType == MediaType.IMAGE,
                    onClick = { viewModel.selectType(MediaType.IMAGE) },
                    label = { Text(stringResource(R.string.media_type_images)) },
                )
                FilterChip(
                    selected = selectedType == MediaType.VIDEO,
                    onClick = { viewModel.selectType(MediaType.VIDEO) },
                    label = { Text(stringResource(R.string.media_type_videos)) },
                )
            }

            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (selectedType == MediaType.IMAGE)
                            stringResource(R.string.media_type_empty_images)
                        else
                            stringResource(R.string.media_type_empty_videos),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                            MediaThumbnail(
                                item = item,
                                onClick = { onItemClick(item.id) },
                                onLongClick = { menuItem = item },
                                isDownloading = item.id in downloadingIds,
                            )
                        }
                    }
                }
            }
            }
            GalleryItemActionsDialog(
                item = menuItem,
                onDismiss = { menuItem = null },
                onInfo = {
                    menuItem = null
                    infoItem = it
                },
                onShare = {
                    if (!GalleryShareHelper.shareMedia(context, it)) {
                        scope.launch {
                            snackbarHostState.showSnackbar(context.getString(R.string.gallery_share_unavailable))
                        }
                    }
                    menuItem = null
                },
                onRemove = {
                    viewModel.removeFromDevice(it)
                    menuItem = null
                },
                onDownload = {
                    viewModel.downloadFromGallery(it)
                    menuItem = null
                },
            )
            GalleryItemInfoDialog(item = infoItem, onDismiss = { infoItem = null })
        }
    }
}
