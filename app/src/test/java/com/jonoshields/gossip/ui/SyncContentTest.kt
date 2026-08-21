package com.jonoshields.gossip.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.jonoshields.gossip.core.model.Ed25519Signer
import com.jonoshields.gossip.core.store.NameResolver
import com.jonoshields.gossip.core.sync.AbortReason
import com.jonoshields.gossip.core.sync.SessionResult
import com.jonoshields.gossip.core.sync.SyncSummary
import com.jonoshields.gossip.sync.DiscoveredPeer
import com.jonoshields.gossip.sync.SyncUiState
import com.jonoshields.gossip.ui.sync.SyncContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The confirmation step is where plan.md §5 step 1's guarantee actually lives on screen —
 * nothing moves until a human answers — so it gets the same scrutiny [ThreadContentTest]
 * gives reply controls: not just that the buttons exist, but that each one fires the
 * callback it claims to.
 */
@RunWith(RobolectricTestRunner::class)
// A tall viewport on purpose: the idle screen's address/port fields and Connect button sit
// below several other elements, and a short default viewport leaves them positioned outside
// the root's laid-out bounds, where a click silently lands nowhere (see ThreadContentTest).
@Config(sdk = [36], qualifiers = "w400dp-h3000dp")
class SyncContentTest {

    @get:Rule val compose = createComposeRule()

    private val peer = Ed25519Signer(ByteArray(32) { it.toByte() }).publicKey

    private var connectedHost: String? = null
    private var connectedPort: Int? = null
    private var confirmed = false
    private var declined = false
    private var cancelled = false
    private var done = false

    private fun show(state: SyncUiState, discoveredPeers: List<DiscoveredPeer> = emptyList()) {
        compose.setContent {
            SyncContent(
                state = state,
                discoveredPeers = discoveredPeers,
                onBack = {},
                onStartListening = {},
                onConnect = { host, port -> connectedHost = host; connectedPort = port },
                onConfirm = { confirmed = true },
                onDecline = { declined = true },
                onCancel = { cancelled = true },
                onDone = { done = true },
            )
        }
    }

    @Test
    fun `connect is disabled until both an address and a port are entered`() {
        show(SyncUiState.Idle)

        compose.onNodeWithText("Connect").assertIsNotEnabled()

        compose.onNodeWithTag("sync-host-field").performTextInput("192.168.1.23")
        compose.onNodeWithTag("sync-host-field").assertTextContains("192.168.1.23")
        compose.onNodeWithText("Connect").assertIsNotEnabled()

        compose.onNodeWithTag("sync-port-field").performTextInput("5000")
        compose.onNodeWithTag("sync-port-field").assertTextContains("5000")
        compose.onNodeWithText("Connect").assertIsEnabled()
        compose.onNodeWithText("Connect").performClick()

        assertEquals("192.168.1.23", connectedHost)
        assertEquals(5000, connectedPort)
    }

    @Test
    fun `tapping a discovered peer connects to its resolved address, not typed input`() {
        show(SyncUiState.Idle, discoveredPeers = listOf(DiscoveredPeer("Gossip (Pixel)", "192.168.1.99", 41234)))

        compose.onNodeWithText("Gossip (Pixel)").performClick()

        assertEquals("192.168.1.99", connectedHost)
        assertEquals(41234, connectedPort)
    }

    @Test
    fun `no peers found says so rather than showing an empty list`() {
        show(SyncUiState.Idle)

        compose.onNodeWithText("Looking for peers on this Wi-Fi network…").assertExists()
    }

    @Test
    fun `the confirmation screen shows the peer's fingerprint, not a name`() {
        // Nobody is a saved contact yet in M3a — every peer is a stranger, and a stranger
        // is shown as a fingerprint, never as bare text that could be mistaken for identity.
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText(NameResolver.fingerprint(peer)).assertExists()
    }

    @Test
    fun `confirming calls onConfirm and nothing else`() {
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText("Yes, sync").performClick()

        assertEquals(true, confirmed)
        assertEquals(false, declined)
    }

    @Test
    fun `declining calls onDecline and nothing else`() {
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText("No").performClick()

        assertEquals(false, confirmed)
        assertEquals(true, declined)
    }

    @Test
    fun `a completed session with new content is summarised`() {
        val result = SessionResult.Completed(SyncSummary(messagesAccepted = 3, profilesAccepted = 1))
        show(SyncUiState.Finished(result))

        compose.onNodeWithText("Fetched 3 messages, 1 names.").assertExists()

        compose.onNodeWithText("Done").performClick()
        assertEquals(true, done)
    }

    @Test
    fun `an aborted session says why, not just that it failed`() {
        val result = SessionResult.Aborted(AbortReason.PEER_DECLINED, SyncSummary())
        show(SyncUiState.Finished(result))

        compose.onNodeWithText("Stopped (peer declined): Nothing new — already up to date.").assertExists()
    }

    @Test
    fun `cancel is offered while listening`() {
        show(SyncUiState.Listening(port = 12345))

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(true, cancelled)
    }

    @Test
    fun `cancel is not offered once a session is finished`() {
        show(SyncUiState.Finished(SessionResult.Completed(SyncSummary())))

        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }
}
