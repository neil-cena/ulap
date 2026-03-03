package com.ulap.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.ulap.R

fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/**
 * Returns a function that runs [action] immediately if POST_NOTIFICATIONS is already granted
 * (or not required on this API level). Otherwise shows an explanation dialog first, then
 * requests the permission and runs [action] if granted.
 */
@Composable
fun rememberRunWithNotificationPermission(action: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    var granted by remember {
        mutableStateOf(
            !needsPermission ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var showExplanationDialog by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        if (result) action()
    }

    if (showExplanationDialog) {
        AlertDialog(
            onDismissRequest = { showExplanationDialog = false },
            title = { Text(stringResource(R.string.permission_notifications_title)) },
            text = { Text(stringResource(R.string.permission_notifications_reason)) },
            confirmButton = {
                TextButton(onClick = {
                    showExplanationDialog = false
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.permission_notifications_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { showExplanationDialog = false }) {
                    Text(stringResource(R.string.permission_notifications_not_now))
                }
            }
        )
    }

    return remember(granted, action) {
        {
            // Re-check the system permission at call time: if it was granted by a previous
            // request in this session, the remembered `granted` flag may not have updated.
            val actuallyGranted = !needsPermission ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (actuallyGranted) {
                if (!granted) granted = true  // sync flag with reality
                action()
            } else {
                showExplanationDialog = true
            }
        }
    }
}
