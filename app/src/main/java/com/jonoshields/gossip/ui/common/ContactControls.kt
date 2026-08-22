package com.jonoshields.gossip.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * What you can privately do about one identity, wherever you're currently looking at it: a
 * nickname — plan.md §3.1's only kind of name shown without a fingerprint, because it's a
 * claim *you* made after confirming the key rather than one the key made about itself — and
 * whether you listen to them.
 *
 * Shared between tapping a name in a thread and the sync confirmation screen, the moment
 * plan.md actually calls out for assigning a nickname: "you confirmed a key in person once,
 * and your local name for it is authoritative from then on."
 */
@Composable
fun ContactControls(
    currentNickname: String?,
    isListening: Boolean,
    onSetNickname: (String) -> Unit,
    onToggleListen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on currentNickname so a save (or a different identity entirely) resets the draft,
    // while typing in between doesn't fight the remembered value.
    var draft by rememberSaveable(currentNickname) { mutableStateOf(currentNickname.orEmpty()) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Nickname") },
            placeholder = { Text("What you call them") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { onSetNickname(draft) },
            enabled = draft.isNotBlank() && draft != currentNickname,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save nickname")
        }

        Row(
            Modifier.fillMaxWidth()
                // The whole row toggles, not just the Switch itself — a bigger, more
                // forgiving touch target, and a stable way to reach this from a test.
                .toggleable(value = isListening, role = Role.Switch, onValueChange = { onToggleListen() }),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (isListening) "Listening to their posts" else "Not listening")
            Switch(checked = isListening, onCheckedChange = null)
        }
        Text(
            // Forward-looking, per plan.md §6: listening never reshuffles what's already held.
            if (isListening) {
                "Turning this off won't remove what you already have — only future syncs stop."
            } else {
                "You'll receive their messages from your next sync onward."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
