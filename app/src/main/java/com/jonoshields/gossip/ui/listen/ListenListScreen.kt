package com.jonoshields.gossip.ui.listen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.ui.common.AuthorName

@Composable
fun ListenListScreen(
    onBack: () -> Unit,
    onOpenContact: (AuthorId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ListenListViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    ListenListContent(
        entries = entries,
        onBack = onBack,
        onOpenContact = onOpenContact,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListenListContent(
    entries: List<ListenEntry>,
    onBack: () -> Unit,
    onOpenContact: (AuthorId) -> Unit,
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
            // Adding by hand-typed key is gone (plan.md's other path, still M4-only
            // otherwise) — in practice everyone you'd listen to, you sync with first, and
            // that confirm screen already offers Listen inline.
            if (entries.isEmpty()) {
                Text(
                    "Not listening to anyone yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.author.toHex() }) { entry ->
                        // Same destination as tapping a name in a thread: change the
                        // nickname, stop listening, or block — all one place, plan.md's
                        // "confirm a key once, manage it from anywhere after" idea.
                        AuthorName(
                            entry.displayName,
                            modifier = Modifier.fillMaxWidth().clickable { onOpenContact(entry.author) },
                        )
                    }
                }
            }
        }
    }
}
