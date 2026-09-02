package com.jonoshields.driftwood.ui.contacts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        onSetNickname = viewModel::setNickname,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactsContent(
    entries: List<ContactEntry>,
    onBack: () -> Unit,
    onOpenContact: (AuthorId) -> Unit,
    onAddContact: () -> Unit,
    onSetNickname: (AuthorId, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var searchText by remember { mutableStateOf("") }
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
                Icon(Icons.Default.Add, contentDescription = "Add contact")
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (entries.isEmpty()) {
                Text(
                    "No claimed names yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (entries.size > 1) {
                    TextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        label = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val filtered = if (searchText.isBlank()) {
                    entries
                } else {
                    entries.filter { entry ->
                        entry.displayName.label?.contains(searchText, ignoreCase = true) == true ||
                            entry.displayName.fingerprint.contains(searchText, ignoreCase = true)
                    }
                }
                // Already sorted verified/followed-first (sortContactEntries) — partition keeps that order per group.
                val (following, everyoneElse) = filtered.partition { it.isFollowing }
                LazyColumn(
                    // Extra bottom clearance so the add-contact FAB never sits over the last entry.
                    contentPadding = PaddingValues(bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (following.isNotEmpty()) {
                        item(key = "header-following") { SectionHeader("Following") }
                        items(following, key = { it.author.toHex() }) { entry ->
                            ContactRow(entry, onOpenContact, onSetNickname)
                        }
                    }
                    if (everyoneElse.isNotEmpty()) {
                        item(key = "header-everyone-else") { SectionHeader("Everyone else") }
                        items(everyoneElse, key = { it.author.toHex() }) { entry ->
                            ContactRow(entry, onOpenContact, onSetNickname)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactRow(
    entry: ContactEntry,
    onOpenContact: (AuthorId) -> Unit,
    onSetNickname: (AuthorId, String) -> Unit,
) {
    var showRenameDialog by remember { mutableStateOf(false) }

    if (showRenameDialog) {
        RenameDialog(
            initialNickname = entry.displayName.nickname.orEmpty(),
            onDismiss = { showRenameDialog = false },
            onConfirm = { nickname ->
                showRenameDialog = false
                onSetNickname(entry.author, nickname)
            },
        )
    }

    // Tap opens the same destination as tapping a name in a thread: nickname, follow, or block.
    // Long-press jumps straight to renaming, since that's the single most common contact action.
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(
                onClick = { onOpenContact(entry.author) },
                onLongClick = { showRenameDialog = true },
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuthorNameExpanded(entry.displayName, entry.author.toHex())
    }
}

@Composable
private fun RenameDialog(initialNickname: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var nickname by remember { mutableStateOf(initialNickname) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("Nickname") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(nickname) }, enabled = nickname.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
