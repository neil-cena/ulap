package com.ulap.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.work.WorkInfo
import com.ulap.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GooglePhotosImportScreen(
    onBack: () -> Unit,
    viewModel: GooglePhotosImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val workInfo by viewModel.workInfo.collectAsState(initial = null)
    val activity = LocalContext.current as Activity

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onSignInActivityResult(result.data)
    }

    val wi = workInfo
    val running = wi?.state == WorkInfo.State.RUNNING
    val showStart = wi == null ||
        wi.state == WorkInfo.State.CANCELLED ||
        wi.state == WorkInfo.State.SUCCEEDED ||
        wi.state == WorkInfo.State.FAILED
    val importInFlight = wi != null && !showStart

    val progressImported = wi?.progress?.getInt("progress", 0) ?: 0
    val progressTotal = wi?.progress?.getInt("total", 0) ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.google_photos_import_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.google_photos_import_body),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { signInLauncher.launch(viewModel.getSignInIntent(activity)) },
                enabled = !state.isBusy && !importInFlight,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.google_photos_sign_in))
            }
            if (state.isBusy) {
                CircularProgressIndicator(modifier = Modifier.height(32.dp))
            }
            if (showStart) {
                Button(
                    onClick = { viewModel.startOrResumeImport() },
                    enabled = state.isSignedIn && !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.google_photos_start_import))
                }
            }
            if (running) {
                Button(
                    onClick = { viewModel.pauseImport() },
                    enabled = true,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.google_photos_pause_import))
                }
            }
            if (importInFlight || progressTotal > 0) {
                Text(
                    text = stringResource(
                        R.string.google_photos_import_progress,
                        progressImported,
                        progressTotal,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (importInFlight) {
                CircularProgressIndicator()
            }
            state.error?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.weight(1f, fill = false))
        }
    }
}
