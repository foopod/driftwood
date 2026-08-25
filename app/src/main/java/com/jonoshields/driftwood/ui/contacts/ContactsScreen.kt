package com.jonoshields.driftwood.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.ui.common.AuthorNameExpanded

@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    onOpenContact: (AuthorId) -> Unit,
    onAddContact: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    ContactsContent(
        entries = entries,
        onBack = onBack,
        onOpenContact = onOpenContact,
        onAddContact = onAddContact,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactsContent(
    entries: List<ConfirmedEntry>,
    onBack: () -> Unit,
    onOpenContact: (AuthorId) -> Unit,
    onAddContact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        floatingActionButtonPosition = FabPosition.End,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddContact,
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ) {
                Text("+", style = MaterialTheme.typography.headlineMedium)
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (entries.isEmpty()) {
                Text(
                    "Nobody confirmed yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Already sorted listened-first (sortConfirmedEntries) — partition keeps that order per group.
                val (listening, everyoneElse) = entries.partition { it.isListening }
                LazyColumn(
                    // Extra bottom clearance so the add-contact FAB never sits over the last entry.
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (listening.isNotEmpty()) {
                        item(key = "header-listening") { SectionHeader("Listening") }
                        items(listening, key = { it.author.toHex() }) { entry ->
                            ContactRow(entry, onOpenContact)
                        }
                    }
                    if (everyoneElse.isNotEmpty()) {
                        item(key = "header-everyone-else") { SectionHeader("Everyone else") }
                        items(everyoneElse, key = { it.author.toHex() }) { entry ->
                            ContactRow(entry, onOpenContact)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ContactRow(entry: ConfirmedEntry, onOpenContact: (AuthorId) -> Unit) {
    // Same destination as tapping a name in a thread: nickname, listen, or block.
    Row(
        Modifier.fillMaxWidth().clickable { onOpenContact(entry.author) },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuthorNameExpanded(entry.displayName, entry.author.toHex())
    }
}
