package com.jonoshields.gossip

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.jonoshields.gossip.core.model.MessageId
import com.jonoshields.gossip.ui.compose.ComposeScreen
import com.jonoshields.gossip.ui.firstrun.FirstRunScreen
import com.jonoshields.gossip.ui.home.HomeScreen
import com.jonoshields.gossip.ui.listen.ListenListScreen
import com.jonoshields.gossip.ui.settings.SettingsScreen
import com.jonoshields.gossip.ui.sync.SyncScreen
import com.jonoshields.gossip.ui.thread.ThreadScreen

/**
 * The app is gated on having a backed-up identity before anything else is reachable.
 *
 * This is not a soft nudge: an identity that has never been written down is one dropped
 * phone away from being gone permanently, and the Keystore copy cannot be recovered from
 * (plan.md §9). The gate is structural — the rest of the app is not in the back stack at
 * all until the phrase is confirmed, so there is nothing to navigate around.
 */
@Composable
fun GossipApp(startWithIdentity: Boolean) {
    var identityReady by remember { mutableStateOf(startWithIdentity) }

    if (!identityReady) {
        FirstRunScreen(
            onFinished = { identityReady = true },
            modifier = Modifier.safeDrawingPadding(),
        )
    } else {
        MainNavigation()
    }
}

@Composable
private fun MainNavigation() {
    val backStack = rememberNavBackStack(Main)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Main> {
                HomeScreen(
                    onOpenThread = { backStack.add(Thread(it.toHex())) },
                    onCompose = { backStack.add(Compose()) },
                    onSettings = { backStack.add(Settings) },
                    onSync = { backStack.add(Sync) },
                    onManageListening = { backStack.add(ListenList) },
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
            entry<Compose> { key ->
                ComposeScreen(
                    replyToRoot = key.replyToRoot?.let(MessageId::fromHex),
                    replyToParent = key.replyToParent?.let(MessageId::fromHex),
                    onDone = { backStack.removeLastOrNull() },
                    onCancel = { backStack.removeLastOrNull() },
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
            entry<Thread> { key ->
                ThreadScreen(
                    rootId = MessageId.fromHex(key.rootId),
                    onReply = { root, parent ->
                        backStack.add(Compose(root.toHex(), parent?.toHex()))
                    },
                    onBack = { backStack.removeLastOrNull() },
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
            entry<Settings> {
                SettingsScreen(
                    onBack = { backStack.removeLastOrNull() },
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
            entry<Sync> {
                SyncScreen(
                    onBack = { backStack.removeLastOrNull() },
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
            entry<ListenList> {
                ListenListScreen(
                    onBack = { backStack.removeLastOrNull() },
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
        },
    )
}
