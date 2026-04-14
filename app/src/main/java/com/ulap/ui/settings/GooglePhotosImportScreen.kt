package com.ulap.ui.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import com.ulap.sync.GooglePhotosImportProgress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GooglePhotosImportScreen(
    onBack: () -> Unit,
    viewModel: GooglePhotosImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val selectedCount = state.selectedMediaCount
    val workInfo by viewModel.workInfo.collectAsState(initial = null)
    val context = LocalContext.current

    val wi = workInfo
    val running = wi?.state == WorkInfo.State.RUNNING
    val workSettled = wi == null ||
        wi.state == WorkInfo.State.CANCELLED ||
        wi.state == WorkInfo.State.SUCCEEDED ||
        wi.state == WorkInfo.State.FAILED
    val importInFlight = wi != null && !(
        wi.state == WorkInfo.State.CANCELLED ||
            wi.state == WorkInfo.State.SUCCEEDED ||
            wi.state == WorkInfo.State.FAILED
        )
    val sessionReadyForImport = state.pickerSessionId != null &&
        !state.isWaitingForPicker &&
        workSettled &&
        !importInFlight
    val showStart = sessionReadyForImport &&
        !state.isCountingSelection &&
        selectedCount != null &&
        selectedCount > 0

    val progressImported = wi?.progress?.getInt(GooglePhotosImportProgress.KEY_IMPORTED, 0) ?: 0
    val progressProcessed = run {
        val p = wi?.progress?.getInt(GooglePhotosImportProgress.KEY_PROCESSED, -1) ?: -1
        if (p >= 0) p else wi?.progress?.getInt("total", 0) ?: 0
    }
    val progressSelectedFromWork = wi?.progress?.getInt(GooglePhotosImportProgress.KEY_SELECTED_TOTAL, 0) ?: 0
    val progressSelectedTotal = when {
        progressSelectedFromWork > 0 -> progressSelectedFromWork
        selectedCount != null && selectedCount > 0 -> selectedCount
        else -> 0
    }
    val barFraction = if (progressSelectedTotal > 0) {
        (progressImported.toFloat() / progressSelectedTotal).coerceIn(0f, 1f)
    } else {
        0f
    }

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

            state.importSuccessSummary?.let { summary ->
                GooglePhotosImportSuccessCard(
                    summary = summary,
                    onDismiss = { viewModel.dismissImportSuccess() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (!state.isSignedIn) {
                Button(
                    onClick = {
                        viewModel.launchSignIn { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                    enabled = !state.isBusy && !importInFlight,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.google_photos_sign_in))
                }
            }

            if (state.isSignedIn) {
                OutlinedButton(
                    onClick = { viewModel.signOut() },
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.google_photos_disconnect_account))
                }
            }

            if (state.isBusy) {
                CircularProgressIndicator(modifier = Modifier.height(32.dp))
            }

            if (state.isSignedIn && state.hasPhotosAccessToken &&
                !state.isWaitingForPicker && state.pickerSessionId == null && !importInFlight
            ) {
                Button(
                    onClick = {
                        viewModel.createPickerSession { uri ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                        }
                    },
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.google_photos_select_photos))
                }
            }

            if (state.isWaitingForPicker) {
                Text(
                    text = stringResource(R.string.google_photos_waiting_for_selection),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                CircularProgressIndicator()
                state.pickerUri?.let { uri ->
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.google_photos_open_picker))
                    }
                }
                OutlinedButton(
                    onClick = { viewModel.cancelPickerSession() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.google_photos_cancel_selection))
                }
            }

            if (sessionReadyForImport) {
                when {
                    state.isCountingSelection -> {
                        Text(
                            text = stringResource(R.string.google_photos_counting_selection),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CircularProgressIndicator()
                    }
                    selectedCount == 0 -> {
                        Text(
                            text = stringResource(R.string.google_photos_no_items_selected),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = { viewModel.cancelPickerSession() },
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.google_photos_cancel_selection))
                        }
                    }
                    selectedCount != null && selectedCount > 0 -> {
                        Text(
                            text = stringResource(R.string.google_photos_selected_count, selectedCount),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (showStart) {
                            Button(
                                onClick = { viewModel.startOrResumeImport() },
                                enabled = !state.isBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.google_photos_start_import))
                            }
                        }
                        OutlinedButton(
                            onClick = { viewModel.cancelPickerSession() },
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.google_photos_cancel_selection))
                        }
                    }
                    else -> {
                        OutlinedButton(
                            onClick = { viewModel.cancelPickerSession() },
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.google_photos_cancel_selection))
                        }
                    }
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

            if (state.importSuccessSummary == null &&
                (importInFlight || progressProcessed > 0 || progressImported > 0)
            ) {
                Text(
                    text = stringResource(
                        R.string.google_photos_import_progress,
                        progressImported,
                        progressProcessed,
                        progressSelectedTotal,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (importInFlight) {
                if (progressSelectedTotal > 0) {
                    LinearProgressIndicator(
                        progress = { barFraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
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
