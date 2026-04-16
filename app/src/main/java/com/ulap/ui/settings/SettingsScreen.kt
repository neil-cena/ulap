package com.ulap.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ulap.BuildConfig
import com.ulap.R
import com.ulap.data.remote.RepairPhase
import com.ulap.data.remote.RepairProgress
import com.ulap.data.repository.UploadSpeedMode
import com.ulap.domain.health.BotHealthStatus
import com.ulap.ui.theme.ThemePreference

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    onNavigateToFolderPicker: () -> Unit,
    onNavigateToQrShow: () -> Unit,
    onNavigateToGooglePhotosImport: () -> Unit = {},
    onNavigateToGooglePhotosSetup: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val themePreference by viewModel.themePreference.collectAsState()
    val stripExif by viewModel.stripExif.collectAsState(initial = false)
    val wifiOnly by viewModel.wifiOnly.collectAsState()
    val pauseOnLowBattery by viewModel.pauseOnLowBattery.collectAsState()
    val uploadSpeedMode by viewModel.uploadSpeedMode.collectAsState()
    val backupStats by viewModel.backupStats.collectAsState()
    val corruptChunkedCount by viewModel.corruptChunkedBackupCount.collectAsState()
    val isFixingCorruptBackups by viewModel.isFixingCorruptBackups.collectAsState()
    val fixCorruptBackupsResult by viewModel.fixCorruptBackupsResult.collectAsState()
    val freeSpace by viewModel.freeSpace.collectAsState()

    val googlePhotosWebClientId by viewModel.googlePhotosWebClientId.collectAsState()

    var showFreeSpaceWarning by remember { mutableStateOf(false) }
    var showFreeSpaceConfirm by remember { mutableStateOf(false) }
    var showAddBotDialog by remember { mutableStateOf(false) }
    var botToRemove by remember { mutableStateOf<Int?>(null) }

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onFreeSpaceDeleteGranted()
        } else {
            viewModel.dismissFreeUpSpace()
        }
    }
    LaunchedEffect(freeSpace.deleteSender) {
        val sender = freeSpace.deleteSender ?: return@LaunchedEffect
        viewModel.consumeFreeSpaceDeleteSender()
        deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        SectionTitle("Appearance")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Theme",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemePreference.entries.forEachIndexed { index, pref ->
                        SegmentedButton(
                            selected = themePreference == pref,
                            onClick = { viewModel.setTheme(pref) },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemePreference.entries.size),
                            label = {
                                Text(
                                    when (pref) {
                                        ThemePreference.SYSTEM -> "System"
                                        ThemePreference.LIGHT -> "Light"
                                        ThemePreference.DARK -> "Dark"
                                    }
                                )
                            },
                        )
                    }
                }
            }
        }

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
        SectionTitle("Bot Pool")
        BotPoolCard(
            entries = state.botPool,
            isAddingBot = state.isAddingBot,
            addBotResult = state.addBotResult,
            repairProgress = state.repairProgress,
            repairResult = state.repairResult,
            onAddBot = { token, label -> viewModel.addBot(token, label) },
            onRemoveBot = { index -> botToRemove = index },
            onDismissResult = viewModel::dismissAddBotResult,
            onRepair = { index -> viewModel.startRepair(index) },
            onPromotePrimary = { viewModel.promotePrimaryBot() },
            onDismissRepairResult = viewModel::dismissRepairResult,
            showAddDialog = showAddBotDialog,
            onShowAddDialog = { showAddBotDialog = true },
            onDismissAddDialog = { showAddBotDialog = false },
        )

        Spacer(Modifier.height(16.dp))
        SectionTitle("Backup")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = onNavigateToFolderPicker,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Manage Backup Folders") }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Strip location from photos", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Removes GPS coordinates from JPEG images before uploading",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = stripExif,
                        onCheckedChange = { viewModel.setStripExif(it) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Wi-Fi only", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Only sync in the background when connected to Wi-Fi",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = wifiOnly,
                        onCheckedChange = { viewModel.setWifiOnly(it) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pause on low battery", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Skip background sync when battery is low",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = pauseOnLowBattery,
                        onCheckedChange = { viewModel.setPauseOnLowBattery(it) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    "Upload speed",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    when (uploadSpeedMode) {
                        UploadSpeedMode.BALANCED -> "Faster backups with standard Telegram rate limiting"
                        UploadSpeedMode.CONSERVATIVE -> "Slower uploads with extra protection against account limits"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    UploadSpeedMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = uploadSpeedMode == mode,
                            onClick = { viewModel.setUploadSpeedMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, UploadSpeedMode.entries.size),
                            label = {
                                Text(
                                    when (mode) {
                                        UploadSpeedMode.BALANCED -> "Balanced"
                                        UploadSpeedMode.CONSERVATIVE -> "Conservative"
                                    }
                                )
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    "Fix backup",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "If large videos show “No chunk metadata” after backup, chunk rows are rebuilt when possible: " +
                        "from the pinned index (file IDs or message IDs), from legacy saved chunk file IDs on the item, " +
                        "or by briefly forwarding chunk messages in this chat to read current Telegram file IDs (forwards are deleted right away).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (corruptChunkedCount == 0) {
                        "Corrupted chunked backups: 0 (none detected)"
                    } else {
                        "Corrupted chunked backups: $corruptChunkedCount"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (corruptChunkedCount > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = viewModel::repairCorruptChunkedBackups,
                    enabled = corruptChunkedCount > 0 && !isFixingCorruptBackups,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isFixingCorruptBackups) "Repairing…" else "Repair chunk metadata")
                }
                fixCorruptBackupsResult?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = viewModel::dismissFixCorruptBackupsResult) {
                        Text("Dismiss")
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SectionTitle("Storage")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (backupStats != null) {
                    val stats = backupStats!!
                    SettingRow("Backed up", formatBytes(stats.backedUpBytes + stats.cloudOnlyBytes))
                    if (stats.pendingBytes > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        SettingRow("Pending upload", formatBytes(stats.pendingBytes))
                    }
                    if (stats.excluded > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            "${stats.excluded} file${if (stats.excluded == 1) "" else "s"} excluded (folder not enabled)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (stats.backedUp > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            "Free up local storage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { showFreeSpaceWarning = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Remove local copies (${formatBytes(stats.backedUpBytes)})")
                        }
                    }
                } else {
                    Text("Loading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
            val uriHandler = LocalUriHandler.current
            Column(modifier = Modifier.padding(16.dp)) {
                SettingRow("Version", BuildConfig.VERSION_NAME)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Ulap backs up your media directly to your Telegram account. No servers, no subscriptions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Community",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Report bugs, get help, or request new features in the Ulap Telegram group.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Image(
                    painter = painterResource(R.drawable.ic_support_group_qr),
                    contentDescription = "Ulap community Telegram group QR code",
                    modifier = Modifier
                        .size(180.dp)
                        .align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { uriHandler.openUri(SupportConstants.SUPPORT_GROUP_URL) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Join the Ulap community") }
            }
        }

        // ── Advanced ──────────────────────────────────────────────────────────
        Spacer(Modifier.height(24.dp))
        SectionTitle("Advanced")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Google Photos Import",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                if (googlePhotosWebClientId != null) {
                    Text(
                        "Import from Google Photos is enabled with your own credentials.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onNavigateToGooglePhotosImport,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.google_photos_import_nav)) }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onNavigateToGooglePhotosSetup,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Change credentials") }
                } else {
                    Text(
                        "Import media from your Google Photos library. " +
                            "Requires registering your own Google Cloud project.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onNavigateToGooglePhotosSetup,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Set up Google Photos import") }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                DiagnosticsSection(viewModel)
            }
        }
        Spacer(Modifier.height(24.dp))
        // ─────────────────────────────────────────────────────────────────────
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

    botToRemove?.let { indexToRemove ->
        AlertDialog(
            onDismissRequest = { botToRemove = null },
            title = { Text("Remove bot?") },
            text = { Text("This bot will be removed from the pool. Items it uploaded will still be downloadable using the primary bot as a fallback.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeBot(indexToRemove)
                    botToRemove = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { botToRemove = null }) { Text("Cancel") }
            },
        )
    }

    // Step 1 — scary warning
    if (showFreeSpaceWarning) {
        AlertDialog(
            onDismissRequest = { showFreeSpaceWarning = false },
            title = { Text("⚠\uFE0F Read this before continuing") },
            text = {
                Column {
                    Text(
                        "Removing local copies is permanent. Once deleted from your device, the only copy of these files will be in your Telegram chat.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "If your Telegram account is banned, your bot is deleted, or you lose access to that chat, those files will be gone forever. Ulap cannot recover them.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Only continue if you understand and accept this risk.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFreeSpaceWarning = false
                        viewModel.prepareFreeUpSpace()
                        showFreeSpaceConfirm = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("I understand, continue") }
            },
            dismissButton = {
                TextButton(onClick = { showFreeSpaceWarning = false }) { Text("Cancel") }
            },
        )
    }

    // Step 2 — final confirm with size
    if (showFreeSpaceConfirm && !freeSpace.isLoading && freeSpace.items.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {
                showFreeSpaceConfirm = false
                viewModel.dismissFreeUpSpace()
            },
            title = { Text("Remove ${freeSpace.items.size} local file${if (freeSpace.items.size == 1) "" else "s"}?") },
            text = {
                Column {
                    Text("This will permanently remove ${formatBytes(freeSpace.totalBytes)} of photos and videos from this device. They are already backed up to Telegram.")
                    if (freeSpace.deleteSender != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Android will show a permission prompt next — that's normal and required.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFreeSpaceConfirm = false
                        if (freeSpace.deleteSender == null) {
                            viewModel.onFreeSpaceDeleteGranted()
                        }
                        // deleteSender set → LaunchedEffect fires the system picker
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFreeSpaceConfirm = false
                    viewModel.dismissFreeUpSpace()
                }) { Text("Cancel") }
            },
        )
    }

}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
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

// ── Bot Pool card ─────────────────────────────────────────────────────────────

@Composable
private fun BotPoolCard(
    entries: List<BotPoolEntry>,
    isAddingBot: Boolean,
    addBotResult: String?,
    repairProgress: RepairProgress?,
    repairResult: String?,
    onAddBot: (token: String, label: String) -> Unit,
    onRemoveBot: (Int) -> Unit,
    onDismissResult: () -> Unit,
    onRepair: (bannedBotIndex: Int) -> Unit,
    onPromotePrimary: () -> Unit,
    onDismissRepairResult: () -> Unit,
    showAddDialog: Boolean,
    onShowAddDialog: () -> Unit,
    onDismissAddDialog: () -> Unit,
) {
    val primaryEntry = entries.firstOrNull()
    val primaryBanned = primaryEntry?.healthStatus == BotHealthStatus.BANNED
    val bannedAlts = entries.drop(1).filter {
        it.healthStatus == BotHealthStatus.BANNED
    }
    val isRepairing = repairProgress?.phase == RepairPhase.RUNNING

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Add extra bots to spread uploads across multiple tokens and reduce rate-limit risk. All bots must be admins in the same chat as the primary bot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // ── Primary bot ban critical alert ─────────────────────────────
            if (primaryBanned) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Primary bot is banned",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Files uploaded by this bot are inaccessible. Promote a healthy secondary bot to restore access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onPromotePrimary,
                            enabled = !isRepairing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Text(if (isRepairing) "Repairing…" else "Promote & Repair")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Bot list ───────────────────────────────────────────────────
            if (entries.size <= 1) {
                Text(
                    "No secondary bots. Uploads use the primary bot only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                entries.drop(1).forEachIndexed { i, entry ->
                    if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val dotColor = when (entry.healthStatus) {
                            BotHealthStatus.HEALTHY -> Color(0xFF4CAF50)
                            BotHealthStatus.BANNED -> MaterialTheme.colorScheme.error
                            BotHealthStatus.UNREACHABLE -> Color(0xFFFFC107)
                            BotHealthStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            val displayLabel = entry.label.ifBlank { "Bot ${entry.index}" }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Spacer(
                                    modifier = Modifier
                                        .then(
                                            Modifier
                                                .height(10.dp)
                                                .then(Modifier.fillMaxWidth(0.04f))
                                        )
                                        .background(dotColor, shape = CircleShape),
                                )
                                Text(displayLabel, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                entry.maskedToken,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (entry.healthStatus == BotHealthStatus.BANNED) {
                                Text(
                                    "Banned — files inaccessible",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else if (entry.healthStatus == BotHealthStatus.UNREACHABLE) {
                                Text(
                                    "Unreachable",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFFC107),
                                )
                            }
                        }
                        Row {
                            if (entry.healthStatus == BotHealthStatus.BANNED) {
                                TextButton(
                                    onClick = { onRepair(entry.index) },
                                    enabled = !isRepairing,
                                ) { Text("Repair") }
                            }
                            IconButton(onClick = { onRemoveBot(entry.index) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove bot",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            // ── Banned alt bots warning banner ─────────────────────────────
            if (bannedAlts.isNotEmpty() && !primaryBanned) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${bannedAlts.size} bot(s) banned. Tap Repair next to each to restore file access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            // ── Repair progress ────────────────────────────────────────────
            repairProgress?.let { progress ->
                if (progress.phase == RepairPhase.RUNNING) {
                    Spacer(Modifier.height(8.dp))
                    if (progress.totalItems > 0) {
                        val fraction = progress.repairedItems.toFloat() / progress.totalItems
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Repairing ${progress.repairedItems}/${progress.totalItems}" +
                                progress.currentItemName?.let { " — $it" }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "Preparing repair…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ── Repair result ──────────────────────────────────────────────
            repairResult?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismissRepairResult) { Text("Dismiss") }
            }

            addBotResult?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismissResult) { Text("Dismiss") }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onShowAddDialog,
                enabled = !isAddingBot,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (isAddingBot) "Verifying…" else "Add bot") }
        }
    }

    if (showAddDialog) {
        AddBotDialog(
            isAdding = isAddingBot,
            onConfirm = { token, label ->
                onAddBot(token, label)
                onDismissAddDialog()
            },
            onDismiss = onDismissAddDialog,
        )
    }
}

@Composable
private fun AddBotDialog(
    isAdding: Boolean,
    onConfirm: (token: String, label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add secondary bot") },
        text = {
            Column {
                Text(
                    "Create a new bot via @BotFather (/newbot), copy the token, and add the bot as an admin to your backup chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bot token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(token, label) },
                enabled = token.isNotBlank() && !isAdding,
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
