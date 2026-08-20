package com.jonoshields.gossip.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.theme.GossipTheme

@Composable
fun ComposeScreen(
    replyToRoot: MessageId?,
    replyToParent: MessageId?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    LaunchedEffect(replyToRoot, replyToParent) { viewModel.bind(replyToRoot, replyToParent) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effect.collect { if (it is ComposeEffect.Posted) onDone() }
    }

    ComposeContent(state, viewModel::updateText, viewModel::send, onCancel, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposeContent(
    state: ComposeUiState,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (state.isReply) "Reply" else "New message") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("Cancel") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.text,
                onValueChange = onTextChange,
                label = { Text(if (state.isReply) "Your reply" else "What's on your mind?") },
                supportingText = {
                    Text(
                        "Write so it stands alone — whoever reads this may never see the " +
                            "rest of the thread."
                    )
                },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
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

@Preview(showBackground = true)
@Composable
private fun ComposePreview() {
    GossipTheme {
        ComposeContent(ComposeUiState(text = "Trying out this gossip thing."), {}, {}, {})
    }
}
