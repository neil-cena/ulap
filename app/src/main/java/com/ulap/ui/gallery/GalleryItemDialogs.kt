package com.ulap.ui.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ulap.R
import com.ulap.domain.model.MediaItem

@Composable
fun GalleryItemActionsDialog(
    item: MediaItem?,
    onDismiss: () -> Unit,
    onInfo: (MediaItem) -> Unit,
    onShare: (MediaItem) -> Unit,
    onRemove: (MediaItem) -> Unit,
    onDownload: (MediaItem) -> Unit,
) {
    val it = item ?: return
    val vis = GalleryContextMenuPolicy.contextMenuVisibility(it)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = it.fileName.ifBlank { it.id },
                maxLines = 2,
            )
        },
        text = {
            Column {
                TextButton(onClick = { onInfo(it) }) {
                    Text(stringResource(R.string.gallery_action_info))
                }
                TextButton(onClick = { onShare(it) }) {
                    Text(stringResource(R.string.gallery_action_share))
                }
                if (vis.showRemoveFromDevice) {
                    TextButton(onClick = { onRemove(it) }) {
                        Text(stringResource(R.string.gallery_action_remove_device))
                    }
                }
                if (vis.showDownload) {
                    TextButton(onClick = { onDownload(it) }) {
                        Text(stringResource(R.string.gallery_action_download))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
fun GalleryItemInfoDialog(
    item: MediaItem?,
    onDismiss: () -> Unit,
) {
    val it = item ?: return
    val lines = GalleryContextMenuPolicy.infoMetadataLinesForDisplay(it)
    val scroll = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gallery_action_info)) },
        text = {
            Column(Modifier.verticalScroll(scroll)) {
                lines.forEach { line ->
                    Text(text = line)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}
