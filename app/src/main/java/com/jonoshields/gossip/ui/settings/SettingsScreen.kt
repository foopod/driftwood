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
    SettingsContent(state, onBack, viewModel::prune, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onPrune: () -> Unit,
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
                Text(
                    state.publicKey ?: "none yet",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "This key is who you are. There is no account behind it.",
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
