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
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.core.store.DisplayName
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
    private val me = Ed25519Signer(ByteArray(32) { (it + 50).toByte() }).publicKey

    private var connectedHost: String? = null
    private var connectedPort: Int? = null
    private var confirmed = false
    private var cancelled = false
    private var done = false
    private var nicknameSet: Pair<AuthorId, String>? = null
    private var listenToggled: AuthorId? = null

    private fun show(
        state: SyncUiState,
        discoveredPeers: List<DiscoveredPeer> = emptyList(),
        names: Map<AuthorId, DisplayName> = emptyMap(),
        listenScope: Set<AuthorId> = emptySet(),
        myAuthor: AuthorId? = me,
    ) {
        compose.setContent {
            SyncContent(
                state = state,
                discoveredPeers = discoveredPeers,
                names = names,
                listenScope = listenScope,
                myAuthor = myAuthor,
                onBack = {},
                onStartListening = {},
                onConnect = { host, port -> connectedHost = host; connectedPort = port },
                onConfirm = { confirmed = true },
                onCancel = { cancelled = true },
                onDone = { done = true },
                onSetNickname = { author, name -> nicknameSet = author to name },
                onToggleListen = { author -> listenToggled = author },
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
    fun `back is offered when idle, and there is nothing to cancel`() {
        show(SyncUiState.Idle)

        compose.onNodeWithText("Back").assertExists()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun `the confirmation screen shows the peer's fingerprint, not a name`() {
        // Nobody is a saved contact yet in M3a — every peer is a stranger, and a stranger
        // is shown as a fingerprint, never as bare text that could be mistaken for identity.
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText(NameResolver.fingerprint(peer)).assertExists()
    }

    @Test
    fun `both fingerprints are shown, each labelled whose it is`() {
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText("Theirs").assertExists()
        compose.onNodeWithText("Mine").assertExists()
        compose.onNodeWithText(NameResolver.fingerprint(peer)).assertExists()
        compose.onNodeWithText(NameResolver.fingerprint(me)).assertExists()
    }

    @Test
    fun `a trust warning is shown before confirming`() {
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText("Only sync with someone you trust", substring = true).assertExists()
    }

    @Test
    fun `there is no back button while confirming, only cancel`() {
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText("Back").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `there is no way to decline outright`() {
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText("No").assertDoesNotExist()
    }

    @Test
    fun `confirming calls onConfirm`() {
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText("Yes, sync").performClick()

        assertEquals(true, confirmed)
    }

    @Test
    fun `confirming relabels the button to waiting and disables it`() {
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText("Yes, sync").performClick()

        compose.onNodeWithText("Yes, sync").assertDoesNotExist()
        compose.onNodeWithText("Waiting…").assertIsNotEnabled()
    }

    @Test
    fun `typing a nickname saves it only once sync starts, not before`() {
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText("Nickname").performTextInput("Sam")
        assertEquals(null, nicknameSet)

        compose.onNodeWithText("Yes, sync").performClick()

        assertEquals(peer, nicknameSet?.first)
        assertEquals("Sam", nicknameSet?.second)
        assertEquals(true, confirmed)
    }

    @Test
    fun `there is no save nickname button`() {
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText("Save nickname").assertDoesNotExist()
    }

    @Test
    fun `toggling listen on the confirm screen calls through for the peer`() {
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText("Not listening").performClick()

        assertEquals(peer, listenToggled)
    }

    @Test
    fun `a known contact sees a plain question, not the fingerprint compare form`() {
        // Meeting them once in person and assigning a nickname was the whole point of the
        // compare-and-confirm ceremony (plan.md §3.1) — repeating it on every subsequent
        // sync would be friction for no new information.
        val nickname = DisplayName(label = "Sam", fingerprint = NameResolver.fingerprint(peer), verified = true, hue = 0f)
        show(SyncUiState.Confirming(peer), names = mapOf(peer to nickname))

        compose.onNodeWithText("Sync with Sam?").assertExists()
        compose.onNodeWithText(nickname.fingerprint).assertDoesNotExist()
        compose.onNodeWithText("Theirs").assertDoesNotExist()
        compose.onNodeWithText("Mine").assertDoesNotExist()
        compose.onNodeWithText("Nickname").assertDoesNotExist()
        compose.onNodeWithText("Not listening").assertDoesNotExist()
        compose.onNodeWithText("Only sync with someone you trust", substring = true).assertDoesNotExist()
    }

    @Test
    fun `confirming a known contact still calls onConfirm and relabels to waiting`() {
        val nickname = DisplayName(label = "Sam", fingerprint = NameResolver.fingerprint(peer), verified = true, hue = 0f)
        show(SyncUiState.Confirming(peer), names = mapOf(peer to nickname))

        compose.onNodeWithText("Yes, sync").performClick()

        assertEquals(true, confirmed)
        compose.onNodeWithText("Waiting…").assertIsNotEnabled()
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
