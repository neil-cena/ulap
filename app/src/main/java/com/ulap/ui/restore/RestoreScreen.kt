package com.ulap.ui.restore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ulap.R
import com.ulap.domain.model.SyncOperation
import com.ulap.ui.rememberRunWithNotificationPermission

@Composable
fun RestoreScreen(viewModel: RestoreViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val progress by viewModel.progress.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val startRestore = rememberRunWithNotificationPermission(viewModel::startRestore)

    LaunchedEffect(Unit) {
        viewModel.completionEvent.collect { event ->
            val message = if (event.failed == 0) {
                context.getString(R.string.restore_complete_body, event.succeeded)
            } else {
                context.getString(R.string.restore_complete_with_failures_body, event.succeeded, event.failed)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(16.dp),
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
            Text("Restore", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Download your backed-up photos and videos from Telegram back to this device. Files will be saved to Pictures/Ulap Restore.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            if (progress.isActive && progress.operation == SyncOperation.DOWNLOADING) {
                Text(
                    "Restoring ${progress.itemsDone} of ${progress.itemsTotal}…",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Button(
                    onClick = startRestore,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.restore_button))
                }
            }
        }
    }
}
