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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ulap.domain.model.SyncOperation

@Composable
fun RestoreScreen(viewModel: RestoreViewModel = hiltViewModel()) {
    val progress by viewModel.progress.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
                onClick = viewModel::startRestore,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Restore All Backed-Up Files")
            }
        }
    }
}
