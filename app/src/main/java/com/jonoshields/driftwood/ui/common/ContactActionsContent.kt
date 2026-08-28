package com.jonoshields.driftwood.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/** What you can privately do about one identity: change their nickname, follow or not, block or not. */
@Composable
fun ContactActionsContent(
    author: AuthorId,
    displayName: DisplayName,
    isFollowing: Boolean,
    isBlocked: Boolean,
    onSetNickname: (String) -> Unit,
    onToggleFollow: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingBlock by remember { mutableStateOf(false) }

    Column(
        // Scrollable so the nickname field/save button stay reachable behind the keyboard.
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ContactIdentityHeader(displayName, author.toHex())

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
                currentNickname = displayName.nickname,
                isFollowing = isFollowing,
                isFollowEnabled = !isBlocked,
                onSetNickname = onSetNickname,
                onToggleFollow = onToggleFollow,
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
