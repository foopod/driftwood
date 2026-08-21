package com.jonoshields.gossip.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.store.DisplayName
import com.jonoshields.gossip.core.store.NameResolver
import com.jonoshields.gossip.sync.DiscoveredPeer
import com.jonoshields.gossip.sync.LocalAddress
import com.jonoshields.gossip.sync.SyncSummaryText
import com.jonoshields.gossip.sync.SyncUiState
import com.jonoshields.gossip.ui.common.AuthorName
import com.jonoshields.gossip.ui.common.ContactControls

@Composable
fun SyncScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val discoveredPeers by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val names by viewModel.names.collectAsStateWithLifecycle()
    val listenScope by viewModel.listenScope.collectAsStateWithLifecycle()
    SyncContent(
        state = state,
        discoveredPeers = discoveredPeers,
        names = names,
        listenScope = listenScope,
        onBack = onBack,
        onStartListening = viewModel::startListening,
        onConnect = viewModel::connectTo,
        onConfirm = viewModel::confirmPeer,
        onDecline = viewModel::declinePeer,
        onCancel = viewModel::cancel,
        onDone = viewModel::reset,
        onSetPetname = viewModel::setPetname,
        onToggleListen = viewModel::toggleListen,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncContent(
    state: SyncUiState,
    discoveredPeers: List<DiscoveredPeer> = emptyList(),
    names: Map<AuthorId, DisplayName> = emptyMap(),
    listenScope: Set<AuthorId> = emptySet(),
    onBack: () -> Unit,
    onStartListening: () -> Unit,
    onConnect: (host: String, port: Int) -> Unit,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onSetPetname: (AuthorId, String) -> Unit,
    onToggleListen: (AuthorId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Sync") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (state !is SyncUiState.Idle && state !is SyncUiState.Finished && state !is SyncUiState.Failed) {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (state) {
                SyncUiState.Idle -> IdleContent(discoveredPeers, onStartListening, onConnect)
                is SyncUiState.Listening -> ListeningContent(state.port)
                is SyncUiState.Connecting -> StatusContent("Connecting to ${state.host}:${state.port}…")
                is SyncUiState.Confirming -> ConfirmingContent(
                    state = state,
                    displayName = names[state.peer] ?: NameResolver.resolve(state.peer, petname = null, claimed = null),
                    isListening = state.peer in listenScope,
                    onConfirm = onConfirm,
                    onDecline = onDecline,
                    onSetPetname = { name -> onSetPetname(state.peer, name) },
                    onToggleListen = { onToggleListen(state.peer) },
                )
                SyncUiState.Running -> StatusContent("Syncing…")
                is SyncUiState.Finished -> FinishedContent(state, onDone)
                is SyncUiState.Failed -> FailedContent(state, onDone)
            }
        }
    }
}

@Composable
private fun IdleContent(
    discoveredPeers: List<DiscoveredPeer>,
    onStartListening: () -> Unit,
    onConnect: (String, Int) -> Unit,
) {
    Text("Nearby", style = MaterialTheme.typography.titleMedium)
    if (discoveredPeers.isEmpty()) {
        Text(
            "Looking for peers on this Wi-Fi network…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            discoveredPeers.forEach { peer ->
                OutlinedButton(
                    onClick = { onConnect(peer.host, peer.port) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(peer.name)
                }
            }
        }
    }

    Text("— or —", style = MaterialTheme.typography.labelMedium)

    Button(onClick = onStartListening, modifier = Modifier.fillMaxWidth()) {
        Text("Listen for a peer")
    }

    Text(
        "If discovery doesn't find them, enter their address directly.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text("Their address") },
        placeholder = { Text("192.168.1.23") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("sync-host-field"),
    )
    OutlinedTextField(
        value = port,
        onValueChange = { port = it.filter(Char::isDigit) },
        label = { Text("Port") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("sync-port-field"),
    )
    val portValue = port.toIntOrNull()
    OutlinedButton(
        onClick = { if (portValue != null) onConnect(host, portValue) },
        enabled = host.isNotBlank() && portValue != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Connect")
    }
}

@Composable
private fun ListeningContent(port: Int) {
    val address = remember { LocalAddress.current() }
    StatusContent("Waiting for someone to connect…")
    Text(
        "Discoverable as \"Nearby\" on their screen. If that doesn't find you: " +
            if (address != null) {
                "tell them to enter $address, port $port."
            } else {
                "tell them to enter this device's address and port $port — couldn't work " +
                    "out the address automatically."
            },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ConfirmingContent(
    state: SyncUiState.Confirming,
    displayName: DisplayName,
    isListening: Boolean,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    onSetPetname: (String) -> Unit,
    onToggleListen: () -> Unit,
) {
    Text("Is this who you're syncing with?", style = MaterialTheme.typography.titleMedium)
    Text(
        "Compare the fingerprint with what they see on their screen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    AuthorName(displayName)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Yes, sync") }
        OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) { Text("No") }
    }

    Text(
        // The moment plan.md actually calls out for a petname: "you confirmed a key in
        // person once, and your local name for it is authoritative from then on." Neither
        // of these needs the sync to actually proceed.
        "Once you've checked the fingerprint in person:",
        style = MaterialTheme.typography.labelMedium,
    )
    ContactControls(
        currentPetname = if (displayName.verified) displayName.label else null,
        isListening = isListening,
        onSetPetname = onSetPetname,
        onToggleListen = onToggleListen,
    )
}

@Composable
private fun FinishedContent(state: SyncUiState.Finished, onDone: () -> Unit) {
    Text("Sync complete", style = MaterialTheme.typography.titleMedium)
    Text(SyncSummaryText.describe(state.result), style = MaterialTheme.typography.bodyLarge)
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun FailedContent(state: SyncUiState.Failed, onDone: () -> Unit) {
    Text("Couldn't sync", style = MaterialTheme.typography.titleMedium)
    Text(state.message, style = MaterialTheme.typography.bodyLarge)
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("OK") }
}

@Composable
private fun StatusContent(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
