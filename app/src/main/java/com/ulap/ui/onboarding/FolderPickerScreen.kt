package com.ulap.ui.onboarding

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ulap.R
import com.ulap.sync.BackupForegroundService
import com.ulap.ui.mediaPermissions
import androidx.hilt.navigation.compose.hiltViewModel
import com.ulap.domain.model.BackupFolder
import com.ulap.ui.rememberRunWithNotificationPermission

@Composable
fun FolderPickerScreen(
    onDone: () -> Unit,
    fromOnboarding: Boolean = false,
    viewModel: FolderPickerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val folders by viewModel.folders.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var permissionGranted by remember {
        val perms = mediaPermissions()
        mutableStateOf(perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permissionGranted = results.values.all { it }
        if (permissionGranted) {
            viewModel.refreshAfterPermission()
        }
    }

    // Request POST_NOTIFICATIONS before starting backup so progress can be shown (Android 13+).
    val startBackupWithPermission = rememberRunWithNotificationPermission {
        BackupForegroundService.startBackup(context)
    }

    // When user enables a folder (toggle), ViewModel emits requestStartBackup.
    // During onboarding: skip the notification permission flow — the "Start Backup" button
    // owns it. Just start the service directly (permission will be requested on button tap).
    // Outside onboarding: run the full permission flow before starting.
    LaunchedEffect(Unit) {
        viewModel.requestStartBackup.collect {
            if (fromOnboarding) {
                BackupForegroundService.startBackup(context)
            } else {
                startBackupWithPermission()
            }
        }
    }

    // During onboarding, request POST_NOTIFICATIONS before calling onDone so the service
    // can post progress notifications immediately on Android 13+.
    val onStartBackupClicked: () -> Unit = if (fromOnboarding) {
        rememberRunWithNotificationPermission {
            viewModel.scanAfterOnboarding()
            onDone()
        }
    } else {
        onDone
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(stringResource(R.string.folder_picker_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.folder_picker_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (!permissionGranted) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.permission_storage_reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(mediaPermissions()) }) {
                    Text(stringResource(R.string.grant_permission))
                }
            }
        } else if (uiState.isLoading) {
            CircularProgressIndicator()
        } else if (folders.isEmpty()) {
            Text(stringResource(R.string.folder_picker_no_folders), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                stringResource(R.string.onboarding_folder_nudge),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(folders, key = { it.bucketName }) { folder ->
                    FolderRow(
                        folder = folder,
                        onToggle = { viewModel.toggle(folder.bucketName, it) },
                    )
                    HorizontalDivider()
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (permissionGranted) {
            Button(
                onClick = onStartBackupClicked,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.hasEnabledAny,
            ) {
                Text(stringResource(R.string.start_backup))
            }
        }
    }
}

@Composable
private fun FolderRow(folder: BackupFolder, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(folder.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.folder_picker_item_count, folder.itemCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = folder.isEnabled, onCheckedChange = onToggle)
    }
}
