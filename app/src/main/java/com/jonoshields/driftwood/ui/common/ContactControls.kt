package com.jonoshields.driftwood.ui.common

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

/** What you can privately do about one identity: set a nickname, and whether you follow them. */
@Composable
fun ContactControls(
    currentNickname: String?,
    isFollowing: Boolean,
    onSetNickname: (String) -> Unit,
    onToggleFollow: () -> Unit,
    // False while blocked — disabled rather than hidden, until you unblock.
    isFollowEnabled: Boolean = true,
    // Hidden on the sync confirm screen, which saves the draft itself via [onDraftChange].
    showSaveButton: Boolean = true,
    onDraftChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Keyed on currentNickname so a save or a different identity resets the draft.
    var draft by rememberSaveable(currentNickname) { mutableStateOf(currentNickname.orEmpty()) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; onDraftChange(it) },
            // "Optional" on the label, not just the placeholder, which only shows once focused.
            label = { Text("Optional nickname") },
            placeholder = { Text("What you call them") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (showSaveButton) {
            OutlinedButton(
                onClick = { onSetNickname(draft) },
                enabled = draft.isNotBlank() && draft != currentNickname,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save nickname")
            }
        }

        Row(
            Modifier.fillMaxWidth()
                // The whole row toggles, not just the Switch — a bigger, more forgiving target.
                .toggleable(
                    value = isFollowing,
                    enabled = isFollowEnabled,
                    role = Role.Switch,
                    onValueChange = { onToggleFollow() },
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (isFollowing) "Following their posts" else "Not following")
            Switch(checked = isFollowing, onCheckedChange = null, enabled = isFollowEnabled)
        }
        Text(
            if (!isFollowEnabled) {
                "You can't follow someone you've blocked — unblock them first."
            } else if (isFollowing) {
                "Turning this off won't remove what you already have — only future syncs stop."
            } else {
                "You'll receive their messages from your next sync onward."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
