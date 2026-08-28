package com.jonoshields.driftwood.ui.sync

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.sync.LocalAddress
import com.jonoshields.driftwood.sync.NearbyPeer
import com.jonoshields.driftwood.sync.SyncLogReport
import com.jonoshields.driftwood.sync.SyncSummaryText
import com.jonoshields.driftwood.sync.SyncUiState
import com.jonoshields.driftwood.ui.common.AuthorName
import com.jonoshields.driftwood.ui.common.ContactControls
import com.jonoshields.driftwood.ui.common.OrDivider

/** How long a connection attempt runs before offering to send a diagnostic log instead of just waiting. */
private const val SLOW_CONNECTION_TIMEOUT_MILLIS = 10_000L

@Composable
fun SyncScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val discoveredPeers by viewModel.discoveredPeers.collectAsStateWithLifecycle()
    val names by viewModel.names.collectAsStateWithLifecycle()
    val followList by viewModel.followList.collectAsStateWithLifecycle()

    // Asked once, opportunistically, on entry — never blocking. Denial just stays LAN-only.
    val context = LocalContext.current
    var wifiDirectPermissionDenied by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) !=
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val requestWifiDirectPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> wifiDirectPermissionDenied = !granted }
    LaunchedEffect(Unit) {
        if (wifiDirectPermissionDenied) requestWifiDirectPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    SyncContent(
        state = state,
        discoveredPeers = discoveredPeers,
        names = names,
        followList = followList,
        myAuthor = viewModel.myAuthor,
        wifiDirectPermissionDenied = wifiDirectPermissionDenied,
        onBack = onBack,
        onStartListening = viewModel::startListening,
        onConnectPeer = { peer -> viewModel.connectTo(peer) },
        onConnectManual = { host, port -> viewModel.connectTo(host, port) },
        onConfirm = viewModel::confirmPeer,
        onCancel = viewModel::cancel,
        onDone = viewModel::reset,
        onFinished = { viewModel.reset(); onBack() },
        onSetNickname = viewModel::setNickname,
        onToggleFollow = viewModel::toggleFollow,
        onSendLog = { SyncLogReport.send(context, viewModel.logSnapshot()) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncContent(
    state: SyncUiState,
    discoveredPeers: List<NearbyPeer> = emptyList(),
    names: Map<AuthorId, DisplayName> = emptyMap(),
    followList: Set<AuthorId> = emptySet(),
    myAuthor: AuthorId? = null,
    wifiDirectPermissionDenied: Boolean = false,
    onBack: () -> Unit,
    onStartListening: () -> Unit,
    onConnectPeer: (NearbyPeer) -> Unit,
    onConnectManual: (host: String, port: Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    // A successful sync returns straight to Home; a failed one returns to this screen's Idle.
    onFinished: () -> Unit,
    onSetNickname: (AuthorId, String) -> Unit,
    onToggleFollow: (AuthorId) -> Unit,
    onSendLog: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Mid-session states offer only Cancel — a plain Back would silently leave a session running.
    val midSession = state !is SyncUiState.Idle && state !is SyncUiState.Finished && state !is SyncUiState.Failed

    // A connection stuck this long is worth a diagnostic, not just more waiting.
    var stuck by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        stuck = false
        if (state is SyncUiState.Listening || state is SyncUiState.Connecting || state is SyncUiState.Running) {
            delay(SLOW_CONNECTION_TIMEOUT_MILLIS)
            stuck = true
        }
    }
    val showSendLog = state is SyncUiState.Failed || stuck

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Sync") },
                navigationIcon = {
                    if (!midSession) TextButton(onClick = onBack) { Text("Back") }
                },
                actions = {
                    if (midSession) {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            // Scrollable so manual-connect's fields and button stay reachable behind the keyboard.
            Modifier.padding(padding).padding(24.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (state) {
                SyncUiState.Idle -> IdleContent(
                    discoveredPeers = discoveredPeers,
                    wifiDirectPermissionDenied = wifiDirectPermissionDenied,
                    onStartListening = onStartListening,
                    onConnectPeer = onConnectPeer,
                    onConnectManual = onConnectManual,
                )
                is SyncUiState.Listening -> ListeningContent(state.port)
                is SyncUiState.Connecting -> StatusContent("Connecting to ${state.label}…")
                is SyncUiState.Confirming -> ConfirmingContent(
                    state = state,
                    displayName = names[state.peer] ?: NameResolver.resolve(state.peer, nickname = null, username = null),
                    // Forced unverified so my fingerprint shows — this is for the other person to check.
                    myDisplayName = myAuthor?.let {
                        (names[it] ?: NameResolver.resolve(it, nickname = null, username = null)).copy(verified = false)
                    },
                    isFollowing = state.peer in followList,
                    onConfirm = onConfirm,
                    onSetNickname = { name -> onSetNickname(state.peer, name) },
                    onToggleFollow = { onToggleFollow(state.peer) },
                )
                SyncUiState.Running -> StatusContent("Syncing…")
                is SyncUiState.Finished -> FinishedContent(state, onFinished)
                is SyncUiState.Failed -> FailedContent(state, onDone)
            }

            if (showSendLog) {
                SendLogButton(onSendLog)
            }
        }
    }
}

@Composable
private fun IdleContent(
    discoveredPeers: List<NearbyPeer>,
    wifiDirectPermissionDenied: Boolean,
    onStartListening: () -> Unit,
    onConnectPeer: (NearbyPeer) -> Unit,
    onConnectManual: (String, Int) -> Unit,
) {
    // One merged list regardless of which radio found a peer.
    Text("Nearby", style = MaterialTheme.typography.titleMedium)
    if (discoveredPeers.isEmpty()) {
        Text(
            "Looking for peers nearby…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            discoveredPeers.forEach { peer ->
                OutlinedButton(
                    onClick = { onConnectPeer(peer) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(peer.name)
                }
            }
        }
    }

    if (wifiDirectPermissionDenied) {
        Text(
            "Wi-Fi Direct needs the Nearby devices permission to sync without shared Wi-Fi — " +
                "enable it in Settings. Syncing over the same Wi-Fi network still works without it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    OrDivider()

    Button(
        onClick = onStartListening,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Make Discoverable")
    }

    // Collapsed by default — a fallback for the rare case discovery doesn't find someone.
    var manualExpanded by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { manualExpanded = !manualExpanded },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Manual Connection", style = MaterialTheme.typography.labelMedium)
        Text(if (manualExpanded) "▾" else "▸")
    }

    if (manualExpanded) {
        Text(
            "If discovery doesn't find them, enter their address directly (same Wi-Fi network only).",
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
            onClick = { if (portValue != null) onConnectManual(host, portValue) },
            enabled = host.isNotBlank() && portValue != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect")
        }
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
    myDisplayName: DisplayName?,
    isFollowing: Boolean,
    onConfirm: () -> Unit,
    onSetNickname: (String) -> Unit,
    onToggleFollow: () -> Unit,
) {
    // Keyed on the peer so a different confirmation starts from a clean slate.
    var confirmed by rememberSaveable(state.peer) { mutableStateOf(false) }
    var nicknameDraft by rememberSaveable(state.peer) { mutableStateOf("") }
    var hashesChecked by rememberSaveable(state.peer) { mutableStateOf(false) }

    // Already confirmed skips only the re-verification ceremony, not the fingerprint/follow toggle.
    val heading = when {
        displayName.verified -> "Sync with ${displayName.text}?"
        displayName.label != null -> "Is this ${displayName.label} you're syncing with?"
        else -> "Is this who you're syncing with?"
    }
    Text(heading, style = MaterialTheme.typography.titleMedium)

    if (!displayName.verified) {
        Text(
            "Compare both fingerprints with what they see on their screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Theirs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AuthorName(displayName)
        }
    }
    myDisplayName?.let {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Mine", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AuthorName(it)
        }
    }

    if (!displayName.verified) {
        Text(
            "Only sync with someone you trust — syncing shares your follow list with them, and theirs with you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = hashesChecked,
                onCheckedChange = { hashesChecked = it },
                modifier = Modifier.testTag("sync-hashes-match-checkbox"),
            )
            Text(
                "I have confirmed the hashes match for the person I am syncing with.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    ContactControls(
        currentNickname = displayName.nickname,
        isFollowing = isFollowing,
        onSetNickname = onSetNickname,
        onToggleFollow = onToggleFollow,
        showSaveButton = false,
        onDraftChange = { nicknameDraft = it },
    )

    ConfirmButton(
        confirmed = confirmed,
        // Already-verified peers skip the ceremony; an unverified peer needs the checkbox first.
        enabled = displayName.verified || hashesChecked,
        onClick = {
            if (nicknameDraft.isNotBlank()) onSetNickname(nicknameDraft)
            confirmed = true
            onConfirm()
        },
    )
}

@Composable
private fun ConfirmButton(confirmed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    // Tagged rather than found by text — "Sync" matches the screen's own TopAppBar title too.
    Button(
        onClick = onClick,
        enabled = !confirmed && enabled,
        modifier = Modifier.fillMaxWidth().testTag("sync-confirm-button"),
    ) {
        Text(if (confirmed) "Waiting…" else "Sync")
    }
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
private fun SendLogButton(onSendLog: () -> Unit) {
    TextButton(onClick = onSendLog, modifier = Modifier.fillMaxWidth()) {
        Text("Send log")
    }
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
