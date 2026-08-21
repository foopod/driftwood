package com.jonoshields.gossip.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onBack = onBack,
        onPrune = viewModel::prune,
        onNicknameChange = viewModel::updateNickname,
        onSaveNickname = viewModel::saveNickname,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onPrune: () -> Unit,
    onNicknameChange: (String) -> Unit,
    onSaveNickname: () -> Unit,
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
                    value = state.nicknameDraft,
                    onValueChange = onNicknameChange,
                    label = { Text("Your name") },
                    placeholder = { Text("no name set") },
                    singleLine = true,
                    isError = state.nicknameError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.nicknameError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.nicknameSaved) {
                    Text(
                        "Saved. People you sync with will see the new name; until then they " +
                            "still hold the old one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedButton(
                    onClick = onSaveNickname,
                    enabled = state.nicknameDraft.isNotBlank() && state.nicknameDraft != state.nickname,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.nickname == null) "Set name" else "Change name")
                }
                Text(
                    state.publicKey ?: "none yet",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "The key is who you are; the name is only a label on it. Anyone can " +
                        "claim any name, so others always see yours next to a short code " +
                        "derived from this key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Storage") {
                Row("Held messages", state.messageCount.toString())
                Row("Window", "${state.windowDays} days")
                Row("Budget", "${state.budgetMegabytes} MB")
                Row("Listen / context / gossip", state.budgetSplit)
                Text(
                    "Caps are not adjustable yet — persisting a user-chosen budget needs " +
                        "somewhere to store it, which lands with the settings work after M1. " +
                        "The defaults are chosen so you never have to touch them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
