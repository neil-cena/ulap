package com.ulap.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.ulap.MainActivity
import com.ulap.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GooglePhotosImportScreen(
    onBack: () -> Unit,
    viewModel: GooglePhotosImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val workInfo by viewModel.workInfo.collectAsState(initial = null)
    val context = LocalContext.current
    val activity = context as Activity
    val mainActivity = context as? MainActivity

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onSignInActivityResult(result.data)
    }

    val googleConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        viewModel.onGoogleConsentActivityResult(result.resultCode)
    }

    LaunchedEffect(state.pendingGoogleConsentIntent) {
        val intent = state.pendingGoogleConsentIntent ?: return@LaunchedEffect
        googleConsentLauncher.launch(intent)
        viewModel.clearPendingConsentIntent()
    }

    DisposableEffect(mainActivity) {
        val act = mainActivity ?: return@DisposableEffect onDispose { }
        val cb: (Int, Intent?) -> Unit = { code, data ->
            viewModel.onGooglePhotosScopePermissionResult(code, data)
        }
        act.googlePhotosScopePermissionResult = cb
        onDispose { act.googlePhotosScopePermissionResult = null }
    }

    val wi = workInfo
    val running = wi?.state == WorkInfo.State.RUNNING
    val showStart = (wi == null ||
        wi.state == WorkInfo.State.CANCELLED ||
        wi.state == WorkInfo.State.SUCCEEDED ||
        wi.state == WorkInfo.State.FAILED) &&
        state.pickerSessionId != null &&
        !state.isWaitingForPicker
    val importInFlight = wi != null && !(
        wi.state == WorkInfo.State.CANCELLED ||
            wi.state == WorkInfo.State.SUCCEEDED ||
            wi.state == WorkInfo.State.FAILED
        )

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

            if (!state.isSignedIn) {
                Button(
                    onClick = { signInLauncher.launch(viewModel.getSignInIntent(activity)) },
                    enabled = !state.isBusy && !importInFlight,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.google_photos_sign_in))
                }
            }

            state.signedInEmail?.takeIf { it.isNotBlank() }?.let { email ->
                Text(
                    text = stringResource(R.string.google_photos_signed_in_as, email),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.needsPlayServicesPhotosScope) {
                Button(
                    onClick = { viewModel.requestPhotosScopePermission(activity) },
                    enabled = !state.isBusy && !importInFlight,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.google_photos_grant_library_scope))
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

            // Step 1: user is ready to pick — show the "Select from Google Photos" button
            if (state.isSignedIn && state.hasPhotosAccessToken &&
                !state.isWaitingForPicker && state.pickerSessionId == null && !importInFlight
            ) {
                Button(
                    onClick = {
                        viewModel.createPickerSession { uri ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                        }
                    },
                    enabled = !state.isBusy && !state.needsPlayServicesPhotosScope,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.google_photos_select_photos))
                }
            }

            // Step 2: session open, waiting for user to finish selecting in Google Photos
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

            // Step 3: selection done, ready to import
            if (showStart) {
                Button(
                    onClick = { viewModel.startOrResumeImport() },
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.google_photos_start_import))
                }
                OutlinedButton(
                    onClick = { viewModel.cancelPickerSession() },
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.google_photos_cancel_selection))
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
