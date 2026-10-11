package com.jonoshields.driftwood.ui.hiddenthreads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.ui.common.AuthorName
import com.jonoshields.driftwood.ui.common.LinkifiedText

@Composable
fun HiddenThreadsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HiddenThreadsViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    HiddenThreadsContent(
        entries = entries,
        onBack = onBack,
        onUnhide = viewModel::unhide,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HiddenThreadsContent(
    entries: List<HiddenThreadEntry>,
    onBack: () -> Unit,
    onUnhide: (MessageId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Hidden threads") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (entries.isEmpty()) {
                Text(
                    "Nothing hidden.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries, key = { it.rootId.toHex() }) { entry ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (entry.displayName != null) {
                                    AuthorName(entry.displayName)
                                } else {
                                    Text(
                                        "a hidden thread",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                entry.text?.let {
                                    LinkifiedText(
                                        it,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            TextButton(
                                onClick = { onUnhide(entry.rootId) },
                                modifier = Modifier.testTag("hidden-thread-unhide"),
                            ) {
                                Text("Unhide")
                            }
                        }
                    }
                }
            }
        }
    }
}
