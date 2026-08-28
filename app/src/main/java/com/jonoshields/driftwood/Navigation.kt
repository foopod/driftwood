package com.jonoshields.driftwood

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.ui.addcontact.AddContactScreen
import com.jonoshields.driftwood.ui.blocklist.BlocklistScreen
import com.jonoshields.driftwood.ui.compose.ComposeScreen
import com.jonoshields.driftwood.ui.contact.ContactScreen
import com.jonoshields.driftwood.ui.contacts.ContactsScreen
import com.jonoshields.driftwood.ui.firstrun.FirstRunScreen
import com.jonoshields.driftwood.ui.home.HomeScreen
import com.jonoshields.driftwood.ui.settings.SettingsScreen
import com.jonoshields.driftwood.ui.sync.SyncScreen
import com.jonoshields.driftwood.ui.thread.ThreadScreen

/** Gated on having a backed-up identity — the rest of the app isn't reachable until then. */
@Composable
fun DriftwoodApp(startWithIdentity: Boolean) {
    var identityReady by remember { mutableStateOf(startWithIdentity) }
    // True only for the session that just finished first-run.
    var justFinishedFirstRun by remember { mutableStateOf(false) }

    if (!identityReady) {
        FirstRunScreen(
            onFinished = {
                justFinishedFirstRun = true
                identityReady = true
            },
            // .imePadding() too: otherwise the keyboard covers whatever's near the bottom
            // of the screen instead of the layout shrinking to make room for it.
            modifier = Modifier.safeDrawingPadding().imePadding(),
        )
    } else {
        MainNavigation(startInIntroMode = justFinishedFirstRun)
    }
}

@Composable
private fun MainNavigation(startInIntroMode: Boolean = false) {
    val backStack = rememberNavBackStack(Main)

    // Guided first post: land straight in Compose, with Home already behind it on the stack.
    LaunchedEffect(Unit) {
        if (startInIntroMode) backStack.add(Compose(introMode = true))
    }

    // .imePadding() so every screen's layout shrinks to make room for the keyboard instead of
    // having it cover whatever's near the bottom (an input field's submit button, typically).
    val screenModifier = Modifier.safeDrawingPadding().imePadding()

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Main> {
                HomeScreen(
                    onOpenThread = { backStack.add(Thread(it.toHex())) },
                    onOpenContact = { backStack.add(Contact(it.toHex())) },
                    onCompose = { backStack.add(Compose()) },
                    onSettings = { backStack.add(Settings) },
                    onSync = { backStack.add(Sync) },
                    onAddContact = { backStack.add(AddContact) },
                    modifier = screenModifier,
                )
            }
            entry<Compose> { key ->
                ComposeScreen(
                    replyToRoot = key.replyToRoot?.let(MessageId::fromHex),
                    replyToParent = key.replyToParent?.let(MessageId::fromHex),
                    onDone = { backStack.removeLastOrNull() },
                    onCancel = { backStack.removeLastOrNull() },
                    introMode = key.introMode,
                    modifier = screenModifier,
                )
            }
            entry<Thread> { key ->
                ThreadScreen(
                    rootId = MessageId.fromHex(key.rootId),
                    onReply = { root, parent ->
                        backStack.add(Compose(root.toHex(), parent?.toHex()))
                    },
                    onBack = { backStack.removeLastOrNull() },
                    onSettings = { backStack.add(Settings) },
                    modifier = screenModifier,
                )
            }
            entry<Settings> {
                SettingsScreen(
                    onBack = { backStack.removeLastOrNull() },
                    onManageContacts = { backStack.add(Contacts) },
                    onManageBlocklist = { backStack.add(Blocklist) },
                    modifier = screenModifier,
                )
            }
            entry<Sync> {
                SyncScreen(
                    onBack = { backStack.removeLastOrNull() },
                    modifier = screenModifier,
                )
            }
            entry<Contacts> {
                ContactsScreen(
                    onBack = { backStack.removeLastOrNull() },
                    onOpenContact = { backStack.add(Contact(it.toHex())) },
                    onAddContact = { backStack.add(AddContact) },
                    modifier = screenModifier,
                )
            }
            entry<Contact> { key ->
                ContactScreen(
                    author = AuthorId.fromHex(key.authorHex),
                    onBack = { backStack.removeLastOrNull() },
                    modifier = screenModifier,
                )
            }
            entry<Blocklist> {
                BlocklistScreen(
                    onBack = { backStack.removeLastOrNull() },
                    onOpenContact = { backStack.add(Contact(it.toHex())) },
                    modifier = screenModifier,
                )
            }
            entry<AddContact> {
                AddContactScreen(
                    onBack = { backStack.removeLastOrNull() },
                    modifier = screenModifier,
                )
            }
        },
    )
}
