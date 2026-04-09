package com.ulap.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GooglePhotosSetupScreen(
    onBack: () -> Unit,
    viewModel: GooglePhotosSetupViewModel = hiltViewModel(),
) {
    val savedClientId by viewModel.savedClientId.collectAsState()
    val fingerprint = viewModel.signingFingerprint
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fieldBringIntoView = remember { BringIntoViewRequester() }

    var clientIdInput by remember(savedClientId) { mutableStateOf(savedClientId ?: "") }
    var showDisableDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Photos Setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                "Bring Your Own Keys",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Google Photos import requires you to register your own Google Cloud project. " +
                    "This gives you full control over API access and keeps your data private to your account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            // Step 1
            SetupStepCard(number = "1", title = "Create a Google Cloud project") {
                Text(
                    "Go to console.cloud.google.com, sign in with your Google account, " +
                        "and create a new project (any name works).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/projectcreate"))
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Google Cloud Console") }
            }

            Spacer(Modifier.height(12.dp))

            // Step 2
            SetupStepCard(number = "2", title = "Enable the Photos Picker API") {
                Text(
                    "Inside your project, open APIs & Services → Library. " +
                        "Search for \"Photos Picker API\" and click Enable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://console.cloud.google.com/apis/library/photospicker.googleapis.com"),
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open API Library") }
            }

            Spacer(Modifier.height(12.dp))

            // Step 3
            SetupStepCard(number = "3", title = "Register this app") {
                Text(
                    "Go to APIs & Services → Credentials → Create Credential → OAuth client ID. " +
                        "Choose Application type: Android. " +
                        "Set the package name to:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                CopyableChip(
                    value = "com.ulap",
                    label = "Package name",
                    context = context,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Set the SHA-1 certificate fingerprint to:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                CopyableChip(
                    value = fingerprint,
                    label = "SHA-1 fingerprint",
                    context = context,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://console.cloud.google.com/apis/credentials/oauthclient"),
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Credentials") }
            }

            Spacer(Modifier.height(12.dp))

            // Step 4
            SetupStepCard(number = "4", title = "Create a Web Client ID") {
                Text(
                    "In the same Credentials page, create a second credential: " +
                        "OAuth client ID → Application type: Web application. " +
                        "Copy the Client ID — it ends in .apps.googleusercontent.com.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "You will paste this into Ulap in step 6.",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Step 5
            SetupStepCard(number = "5", title = "Configure the OAuth consent screen") {
                Text(
                    "Go to APIs & Services → OAuth consent screen. Choose External. " +
                        "Fill in the required app name and support email fields.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Keep publishing status as Testing — do not publish. " +
                            "Google blocks the Photos Picker scope for published or unverified apps, " +
                            "so Testing mode is required. Under Test users, add your own Google account email.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://console.cloud.google.com/apis/credentials/consent"),
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Consent Screen") }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    "Then open the Scopes page and click \"Add or remove scopes\". " +
                        "Search for and add the following scope:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                CopyableChip(
                    value = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly",
                    label = "Required scope",
                    context = context,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://console.cloud.google.com/auth/scopes"),
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Scopes Page") }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // Step 6 — paste the key
            Text(
                "Step 6 — Paste your Web Client ID",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = clientIdInput,
                onValueChange = { clientIdInput = it },
                label = { Text("Web Client ID") },
                placeholder = { Text("xxxxxxxx.apps.googleusercontent.com") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(fieldBringIntoView)
                    .onFocusEvent { if (it.isFocused) scope.launch { fieldBringIntoView.bringIntoView() } },
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    viewModel.saveClientId(clientIdInput)
                    onBack()
                },
                enabled = clientIdInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save and enable Google Photos import") }

            if (savedClientId != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showDisableDialog = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Remove credentials and disable Google Photos import") }
            }

            if (showDisableDialog) {
                AlertDialog(
                    onDismissRequest = { showDisableDialog = false },
                    title = { Text("Disable Google Photos import?") },
                    text = {
                        Text(
                            "This will remove your Web Client ID. " +
                                "You will need to paste it again to re-enable Google Photos import.",
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.clearClientId()
                                clientIdInput = ""
                                showDisableDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) { Text("Disable") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showDisableDialog = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }

            val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
            Spacer(Modifier.height(imeBottom.coerceAtLeast(24.dp)))
        }
    }
}

@Composable
private fun SetupStepCard(
    number: String,
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Step $number",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun CopyableChip(value: String, label: String, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
            },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Copy $label",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
