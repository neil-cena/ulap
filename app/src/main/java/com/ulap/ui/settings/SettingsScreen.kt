package com.ulap.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SettingsScreen(
    onNavigateToFolderPicker: () -> Unit,
    onNavigateToQrShow: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        SectionTitle("Account")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingRow("Bot Token", state.maskedToken)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SettingRow("Chat ID", state.chatId)
                Spacer(Modifier.height(12.dp))
                state.verifyResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = viewModel::verifyConnection,
                    enabled = !state.isVerifying,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.isVerifying) "Verifying…" else "Verify Connection") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = viewModel::requestClear,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Disconnect Bot") }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("Backup")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = onNavigateToFolderPicker,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Manage Backup Folders") }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("Other devices")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Setting up Ulap on another phone? Show this QR code on that phone to transfer your settings instantly — no typing required.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onNavigateToQrShow,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Show QR code for another phone") }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("About")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingRow("Version", "1.0.0")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Ulap backs up your media directly to your Telegram account. No servers, no subscriptions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (state.showClearConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClear,
            title = { Text("Disconnect Bot?") },
            text = { Text("This will remove your bot token and chat ID from this device. Your Telegram backups will not be deleted.") },
            confirmButton = {
                TextButton(onClick = viewModel::clearAccount) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClear) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SettingRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
    }
}
