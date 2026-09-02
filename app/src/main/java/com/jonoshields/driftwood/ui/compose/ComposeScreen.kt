package com.jonoshields.driftwood.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.theme.DriftwoodTheme
import com.jonoshields.driftwood.ui.common.LinkifiedText

@Composable
fun ComposeScreen(
    replyToRoot: MessageId?,
    replyToParent: MessageId?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    introMode: Boolean = false,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    LaunchedEffect(replyToRoot, replyToParent) { viewModel.bind(replyToRoot, replyToParent) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effect.collect { if (it is ComposeEffect.Posted) onDone() }
    }

    ComposeContent(state, viewModel::updateText, viewModel::send, onCancel, modifier, introMode)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposeContent(
    state: ComposeUiState,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    introMode: Boolean = false,
) {
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val requestCancel = { if (state.text.isBlank()) onCancel() else showDiscardConfirm = true }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard this draft?") },
            text = { Text("What you've written so far will be lost.") },
            confirmButton = {
                TextButton(onClick = { showDiscardConfirm = false; onCancel() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("Keep writing") }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (state.isReply) "Reply" else "New post") },
                navigationIcon = { TextButton(onClick = requestCancel) { Text("Cancel") } },
            )
        },
    ) { padding ->
        Column(
            // Scrollable so the send button stays reachable once the keyboard and a long
            // reply (the field has no max height) together outgrow the available space.
            Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ReplyContext(state.target)

            // introMode only ever reaches a fresh post — a gentler prompt for guided first-run.
            if (introMode && !state.isReply) {
                Text(
                    "Say hello — this is the first thing others will see from you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            OutlinedTextField(
                value = state.text,
                onValueChange = onTextChange,
                label = { Text(if (state.isReply) "Your reply" else "What's on your mind?") },
                minLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (state.canSend) onSend() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${state.remaining}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.remaining < 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "${state.remaining} characters remaining"
                    },
                )
                Button(onClick = onSend, enabled = state.canSend) {
                    Text(if (state.isReply) "Reply" else "Post")
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Shows what the reply attaches to — a thread, and optionally one message inside it. */
@Composable
private fun ReplyContext(target: ReplyTarget) {
    when (target) {
        ReplyTarget.None -> Unit

        is ReplyTarget.Message -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Replying to",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinkifiedText(
                    target.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        ReplyTarget.Thread -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Replying to this thread", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Not to any one message — which is fine, and is what happens when the " +
                        "message you're answering isn't carried here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ComposePreview() {
    DriftwoodTheme {
        ComposeContent(ComposeUiState(text = "Trying out this gossip thing."), {}, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ReplyPreview() {
    DriftwoodTheme {
        ComposeContent(
            ComposeUiState(
                text = "Yes, exactly this.",
                target = ReplyTarget.Message("The start of the conversation, which we are answering."),
            ),
            {}, {}, {},
        )
    }
}
