package com.jonoshields.gossip.ui.listen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.ui.common.AuthorName

@Composable
fun ListenListScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ListenListViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    ListenListContent(
        entries = entries,
        onBack = onBack,
        onListenTo = viewModel::listenTo,
        onStopListening = viewModel::stopListening,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListenListContent(
    entries: List<ListenEntry>,
    onBack: () -> Unit,
    onListenTo: (AuthorId) -> Unit,
    onStopListening: (AuthorId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Listening to") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AddByKey(onListenTo)

            HorizontalDivider()

            if (entries.isEmpty()) {
                Text(
                    "Not listening to anyone yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.author.toHex() }) { entry ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AuthorName(entry.displayName)
                            TextButton(onClick = { onStopListening(entry.author) }) { Text("Stop") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddByKey(onListenTo: (AuthorId) -> Unit) {
    // Promotion from a thread you've already seen goes through the contact-actions screen
    // (tap a name → Listen); this is the other path plan.md names — for someone you haven't
    // gossiped with yet and have no QR flow for (that's still M4).
    var keyInput by rememberSaveable { mutableStateOf("") }
    var keyError by rememberSaveable { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Listen to a key you already have", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it; keyError = null },
            label = { Text("Public key (hex)") },
            isError = keyError != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("listen-key-field"),
        )
        keyError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                val author = runCatching { AuthorId.fromHex(keyInput.trim()) }.getOrNull()
                if (author == null) {
                    keyError = "That doesn't look like a valid key"
                } else {
                    onListenTo(author)
                    keyInput = ""
                }
            },
            enabled = keyInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Listen")
        }
    }
}
