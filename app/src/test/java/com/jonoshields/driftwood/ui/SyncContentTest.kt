package com.jonoshields.driftwood.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.jonoshields.driftwood.core.model.Ed25519Signer
import com.jonoshields.driftwood.core.store.NameResolver
import com.jonoshields.driftwood.core.sync.AbortReason
import com.jonoshields.driftwood.core.sync.SessionResult
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.store.DisplayName
import com.jonoshields.driftwood.core.sync.SyncSummary
import com.jonoshields.driftwood.sync.NearbyPeer
import com.jonoshields.driftwood.sync.PeerRef
import com.jonoshields.driftwood.sync.SyncUiState
import com.jonoshields.driftwood.ui.sync.SyncContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The confirmation step is where "nothing moves until a human answers" lives on screen, so it gets the same scrutiny [ThreadContentTest] gives reply controls. */
@RunWith(RobolectricTestRunner::class)
// Tall viewport: the idle screen's address/port fields and Connect button need room below other elements.
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
    private var finished = false
    private var nicknameSet: Pair<AuthorId, String>? = null
    private var listenToggled: AuthorId? = null

    private fun show(
        state: SyncUiState,
        discoveredPeers: List<NearbyPeer> = emptyList(),
        names: Map<AuthorId, DisplayName> = emptyMap(),
        listenScope: Set<AuthorId> = emptySet(),
        myAuthor: AuthorId? = me,
        wifiDirectPermissionDenied: Boolean = false,
    ) {
        compose.setContent {
            SyncContent(
                state = state,
                discoveredPeers = discoveredPeers,
                names = names,
                listenScope = listenScope,
                myAuthor = myAuthor,
                wifiDirectPermissionDenied = wifiDirectPermissionDenied,
                onBack = {},
                onStartListening = {},
                onConnectPeer = { peer ->
                    val ref = peer.ref as PeerRef.Lan
                    connectedHost = ref.host
                    connectedPort = ref.port
                },
                onConnectManual = { host, port -> connectedHost = host; connectedPort = port },
                onConfirm = { confirmed = true },
                onCancel = { cancelled = true },
                onDone = { done = true },
                onFinished = { finished = true },
                onSetNickname = { author, name -> nicknameSet = author to name },
                onToggleListen = { author -> listenToggled = author },
            )
        }
    }

    @Test
    fun `manual connection fields are collapsed until the section is expanded`() {
        show(SyncUiState.Idle)

        compose.onNodeWithTag("sync-host-field").assertDoesNotExist()

        compose.onNodeWithText("Manual Connection").performClick()

        compose.onNodeWithTag("sync-host-field").assertExists()
    }

    @Test
    fun `connect is disabled until both an address and a port are entered`() {
        show(SyncUiState.Idle)

        compose.onNodeWithText("Manual Connection").performClick()

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
        val peer = NearbyPeer("driftwood (Pixel)", PeerRef.Lan("192.168.1.99", 41234))
        show(SyncUiState.Idle, discoveredPeers = listOf(peer))

        compose.onNodeWithText("driftwood (Pixel)").performClick()

        assertEquals("192.168.1.99", connectedHost)
        assertEquals(41234, connectedPort)
    }

    @Test
    fun `no peers found says so rather than showing an empty list`() {
        show(SyncUiState.Idle)

        compose.onNodeWithText("Looking for peers nearby…").assertExists()
    }

    @Test
    fun `a wifi direct peer in the merged list connects over wifi direct, not LAN`() {
        var connectedPeer: NearbyPeer? = null
        val peer = NearbyPeer("Bob's Phone", PeerRef.WifiDirect("aa:bb:cc:dd:ee:ff"))
        compose.setContent {
            SyncContent(
                state = SyncUiState.Idle,
                discoveredPeers = listOf(peer),
                onBack = {},
                onStartListening = {},
                onConnectPeer = { connectedPeer = it },
                onConnectManual = { _, _ -> },
                onConfirm = {},
                onCancel = {},
                onDone = {},
                onFinished = {},
                onSetNickname = { _, _ -> },
                onToggleListen = {},
            )
        }

        compose.onNodeWithText("Bob's Phone").performClick()

        assertEquals(peer, connectedPeer)
    }

    @Test
    fun `connecting shows the given label, whichever transport it came from`() {
        show(SyncUiState.Connecting("Bob's Phone"))

        compose.onNodeWithText("Connecting to Bob's Phone…").assertExists()
    }

    @Test
    fun `a permission-denied hint is shown when asked for, and not otherwise`() {
        show(SyncUiState.Idle, wifiDirectPermissionDenied = true)
        compose.onNodeWithText("Nearby devices permission", substring = true).assertExists()
    }

    @Test
    fun `no permission hint is shown once granted`() {
        show(SyncUiState.Idle, wifiDirectPermissionDenied = false)
        compose.onNodeWithText("Nearby devices permission", substring = true).assertDoesNotExist()
    }

    @Test
    fun `back is offered when idle, and there is nothing to cancel`() {
        show(SyncUiState.Idle)

        compose.onNodeWithText("Back").assertExists()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun `the confirmation screen shows the peer's fingerprint, not a name`() {
        // An unconfirmed peer is shown as a fingerprint, never as bare text mistakable for identity.
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
    fun `mine always shows my fingerprint, even though my own name is locally verified`() {
        // My own "verified" name must still show a fingerprint here — this display is for the other person to check.
        val myClaimedName = DisplayName(label = "JonoName", fingerprint = NameResolver.fingerprint(me), verified = true, hue = 0f)
        show(SyncUiState.Confirming(peer), names = mapOf(me to myClaimedName))

        compose.onNodeWithText("JonoName", substring = true).assertExists()
        compose.onNodeWithText(NameResolver.fingerprint(me), substring = true).assertExists()
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

        compose.onNodeWithTag("sync-confirm-button").performClick()

        assertEquals(true, confirmed)
    }

    @Test
    fun `confirming relabels the button to waiting and disables it`() {
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithTag("sync-confirm-button").performClick()

        compose.onNodeWithTag("sync-confirm-button").assertTextEquals("Waiting…").assertIsNotEnabled()
    }

    @Test
    fun `typing a nickname saves it only once sync starts, not before`() {
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText("Optional nickname").performTextInput("Sam")
        assertEquals(null, nicknameSet)

        compose.onNodeWithTag("sync-confirm-button").performClick()

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
    fun `a known contact skips re-verifying their fingerprint, but still shows mine and the listen toggle`() {
        // A known contact skips re-comparing fingerprints, but still shows mine and the listen toggle.
        val nickname = DisplayName(label = "Sam", fingerprint = NameResolver.fingerprint(peer), verified = true, hue = 0f)
        show(SyncUiState.Confirming(peer), names = mapOf(peer to nickname))

        compose.onNodeWithText("Sync with Sam?").assertExists()
        compose.onNodeWithText(nickname.fingerprint).assertDoesNotExist()
        compose.onNodeWithText("Theirs").assertDoesNotExist()
        compose.onNodeWithText("Only sync with someone you trust", substring = true).assertDoesNotExist()

        compose.onNodeWithText("Mine").assertExists()
        compose.onNodeWithText(NameResolver.fingerprint(me)).assertExists()
        compose.onNodeWithText("Optional nickname").assertExists()
        compose.onNodeWithText("Not listening").assertExists()
    }

    @Test
    fun `an unconfirmed peer with a claimed username is named in the question`() {
        val claimed = DisplayName(label = "Dave", fingerprint = NameResolver.fingerprint(peer), verified = false, hue = 0f)
        show(SyncUiState.Confirming(peer), names = mapOf(peer to claimed))

        compose.onNodeWithText("Is this Dave you're syncing with?").assertExists()
    }

    @Test
    fun `an unconfirmed peer with no claim at all gets the generic question`() {
        show(SyncUiState.Confirming(peer))

        compose.onNodeWithText("Is this who you're syncing with?").assertExists()
    }

    @Test
    fun `confirming a known contact still calls onConfirm and relabels to waiting`() {
        val nickname = DisplayName(label = "Sam", fingerprint = NameResolver.fingerprint(peer), verified = true, hue = 0f)
        show(SyncUiState.Confirming(peer), names = mapOf(peer to nickname))

        compose.onNodeWithTag("sync-confirm-button").performClick()

        assertEquals(true, confirmed)
        compose.onNodeWithTag("sync-confirm-button").assertTextEquals("Waiting…").assertIsNotEnabled()
    }

    @Test
    fun `a completed session with new content is summarised`() {
        val result = SessionResult.Completed(SyncSummary(messagesAccepted = 3, profilesAccepted = 1))
        show(SyncUiState.Finished(result))

        compose.onNodeWithText("Fetched 3 messages, 1 names.").assertExists()

        // A successful sync returns to Home rather than back to this screen's Idle state —
        // there's nothing left to do here once it's done.
        compose.onNodeWithText("Done").performClick()
        assertEquals(true, finished)
        assertEquals(false, done)
    }

    @Test
    fun `a failed session's OK resets in place rather than leaving the screen`() {
        show(SyncUiState.Failed("Connection refused"))

        compose.onNodeWithText("OK").performClick()

        assertEquals(true, done)
        assertEquals(false, finished)
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
