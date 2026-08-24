package com.jonoshields.driftwood.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.store.DisplayName

/** What you can privately do about one identity: change their nickname, listen or not, block or not. */
@Composable
fun ContactActionsContent(
    author: AuthorId,
    displayName: DisplayName,
    isListening: Boolean,
    isBlocked: Boolean,
    onSetNickname: (String) -> Unit,
    onToggleListen: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingBlock by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AuthorNameExpanded(displayName, author.toHex())

        if (confirmingBlock) {
            Text(
                "Blocks them: removes their messages and the threads they started, " +
                    "immediately and from this device only, and stops listening to them " +
                    "too. They are never told.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { confirmingBlock = false }) { Text("Cancel") }
                TextButton(onClick = { confirmingBlock = false; onBlock() }) {
                    Text("Block", color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            ContactControls(
                currentNickname = if (displayName.verified) displayName.label else null,
                isListening = isListening,
                isListenEnabled = !isBlocked,
                onSetNickname = onSetNickname,
                onToggleListen = onToggleListen,
            )
            if (isBlocked) {
                TextButton(onClick = onUnblock) { Text("Unblock") }
            } else {
                TextButton(onClick = { confirmingBlock = true }) {
                    Text("Block", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
