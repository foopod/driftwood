package com.jonoshields.gossip.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageListening: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onBack = onBack,
        onPrune = viewModel::prune,
        onUsernameChange = viewModel::updateUsername,
        onSaveUsername = viewModel::saveUsername,
        onSyncWithDebugPeer = viewModel::syncWithDebugPeer,
        onManageListening = onManageListening,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onPrune: () -> Unit,
    onUsernameChange: (String) -> Unit,
    onSaveUsername: () -> Unit,
    onSyncWithDebugPeer: () -> Unit,
    onManageListening: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Your identity") {
                OutlinedTextField(
                    value = state.usernameDraft,
                    onValueChange = onUsernameChange,
                    label = { Text("Your username") },
                    placeholder = { Text("no username set") },
                    singleLine = true,
                    isError = state.usernameError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.usernameError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.usernameSaved) {
                    Text(
                        "Saved. People you sync with will see the new username; until then " +
                            "they still hold the old one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedButton(
                    onClick = onSaveUsername,
                    enabled = state.usernameDraft.isNotBlank() && state.usernameDraft != state.username,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.username == null) "Set username" else "Change username")
                }
                Text(
                    state.publicKey ?: "none yet",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "The key is who you are; the username is only a label on it. Anyone can " +
                        "claim any username, so others always see yours next to a short code " +
                        "derived from this key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Listening") {
                Text(
                    "Choose whose posts always land in the Listening tab, wherever they " +
                        "turn up in the network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onManageListening, modifier = Modifier.fillMaxWidth()) {
                    Text("Manage listening list")
                }
            }

            Section("Storage") {
                Row("Held messages", state.messageCount.toString())
                Row("Window", "${state.windowDays} days")
                Row("Budget", "${state.budgetMegabytes} MB")
                Row("Listen / context / gossip", state.budgetSplit)
            }

            Section("Maintenance") {
                Text(
                    "Pruning normally runs after a sync. There is no sync yet, so this " +
                        "button stands in for it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onPrune, modifier = Modifier.fillMaxWidth()) {
                    Text("Run pruning now")
                }
                state.lastPruneSummary?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Section("Debug peer") {
                Text(
                    "There is no real transport yet (M3a). This runs a genuine sync " +
                        "session — real framing, verification and ingest — against an " +
                        "in-process peer holding a few invented identities, so the " +
                        "protocol can be seen working before any radio exists.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onSyncWithDebugPeer,
                    enabled = !state.debugSyncRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.debugSyncRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Sync with debug peer")
                    }
                }
                state.debugSyncSummary?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun Row(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
