package com.jonoshields.gossip.ui.common

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
import com.jonoshields.gossip.core.store.DisplayName

/**
 * What you can privately do about one identity: read or change their nickname, listen to
 * them or not, block them or not.
 *
 * Shared between tapping a name in a thread (`ThreadScreen`'s in-place content-swap) and the
 * dedicated `ContactScreen` reached from the listening list — [onClose] is only relevant to
 * the former, since a real screen already has its own way back.
 *
 * You can't listen to and block the same person (plan.md §4: blocked wins over everything,
 * unconditionally). Block always stays tappable — you might need it urgently regardless of
 * current listen state — and turning it on also stops listening, so the data never disagrees
 * with that rule. While blocked, Listen is disabled; unblocking doesn't restore it, since
 * that would silently undo half of a deliberate, confirmed action.
 */
@Composable
fun ContactActionsContent(
    displayName: DisplayName,
    isListening: Boolean,
    isBlocked: Boolean,
    onSetNickname: (String) -> Unit,
    onToggleListen: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var confirmingBlock by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AuthorName(displayName)

        if (confirmingBlock) {
            // Plain, per plan.md §6: what blocking actually does, stated rather than
            // implied — this is the moment that decides, not the button that started it.
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (isBlocked) {
                    TextButton(onClick = onUnblock) { Text("Unblock") }
                } else {
                    TextButton(onClick = { confirmingBlock = true }) {
                        Text("Block", color = MaterialTheme.colorScheme.error)
                    }
                }
                onClose?.let { close -> TextButton(onClick = close) { Text("Close") } }
            }
        }
    }
}
