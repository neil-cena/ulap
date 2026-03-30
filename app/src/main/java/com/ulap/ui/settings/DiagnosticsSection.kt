package com.ulap.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
private fun DiagnosticsSectionTitle() {
    Text(
        "Diagnostics",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
internal fun DiagnosticsSection(viewModel: SettingsViewModel) {
    val loggingEnabled by viewModel.telegramLoggingEnabled.collectAsState()
    val savedChatId by viewModel.telegramLoggingChatId.collectAsState()

    var chatIdInput by remember(savedChatId) { mutableStateOf(savedChatId ?: "") }

    DiagnosticsSectionTitle()

    Spacer(Modifier.height(8.dp))
    Text(
        "Send app logs to a Telegram chat for remote debugging. " +
            "Uses the same bot token as your backup. " +
            "Enter a separate chat/channel ID where the bot has been added as admin.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    HorizontalDivider()
    Spacer(Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Send logs to Telegram", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Streams all debug logs, lifecycle events, and video player state to Telegram",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = loggingEnabled,
            onCheckedChange = { viewModel.setTelegramLoggingEnabled(it) },
        )
    }

    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = chatIdInput,
        onValueChange = { chatIdInput = it },
        label = { Text("Logging chat ID") },
        placeholder = { Text("-100xxxxxxxxxx") },
        supportingText = {
            Text(
                "Create a Telegram channel/group, add your backup bot as admin, and paste its chat ID here.",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        enabled = true,
    )

    Spacer(Modifier.height(8.dp))

    OutlinedButton(
        onClick = { viewModel.setTelegramLoggingChatId(chatIdInput.trim().ifBlank { null }) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Save logging chat ID")
    }

    Spacer(Modifier.height(4.dp))
    val displayChatId = savedChatId
    if (displayChatId != null) {
        Text(
            "Current: $displayChatId",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
}
