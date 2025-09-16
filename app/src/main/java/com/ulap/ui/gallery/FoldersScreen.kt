package com.ulap.ui.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ulap.domain.model.BackupFolder

@Composable
fun FoldersScreen(
    onFolderClick: (String) -> Unit,
    viewModel: FoldersViewModel = hiltViewModel(),
) {
    val folders by viewModel.folders.collectAsState()

    if (folders.isEmpty()) {
        Text("No folders found.", modifier = Modifier.padding(24.dp))
        return
    }

    LazyColumn {
        items(folders, key = { it.bucketName }) { folder ->
            FolderListItem(
                folder = folder,
                onToggle = { viewModel.toggle(folder.bucketName, it) },
                onClick = { onFolderClick(folder.bucketName) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun FolderListItem(
    folder: BackupFolder,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(folder.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "${folder.backedUpCount} / ${folder.itemCount} backed up",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (folder.isEnabled && folder.itemCount > 0) {
                LinearProgressIndicator(
                    progress = { folder.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = folder.isEnabled, onCheckedChange = onToggle)
    }
}
