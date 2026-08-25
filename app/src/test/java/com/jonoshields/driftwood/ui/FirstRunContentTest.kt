package com.jonoshields.driftwood.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.jonoshields.driftwood.ui.firstrun.FirstRunActions
import com.jonoshields.driftwood.ui.firstrun.FirstRunContent
import com.jonoshields.driftwood.ui.firstrun.FirstRunUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** First-run screen behavior, driven by state and callbacks directly (no ViewModel/DI), run under Robolectric since the device blocks adb input injection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirstRunContentTest {

    @get:Rule val compose = createComposeRule()

    private val phrase = listOf(
        "clump", "kiss", "volume", "raw", "bright", "surge", "horn", "green",
        "abuse", "run", "concert", "raven", "pony", "skull", "code", "ring",
        "subject", "repair", "palm", "goddess", "little", "quarter", "light", "siren",
    )

    private var created = 0
    private var verified = 0
    private val answers = mutableMapOf<Int, String>()
    private var typedUsername = ""
    private var usernameSubmitted = 0
    private var usernameSkipped = 0

    private fun actions() = FirstRunActions(
        onCreate = { created++ },
        onBeginRestore = {},
        onRestoreInputChange = {},
        onSubmitRestore = {},
        onBeginVerification = {},
        onVerificationAnswerChange = { slot, value -> answers[slot] = value },
        onSubmitVerification = { verified++ },
        onShowPhraseAgain = {},
        onUsernameChange = { typedUsername = it },
        onSubmitUsername = { usernameSubmitted++ },
        onSkipUsername = { usernameSkipped++ },
        onFinished = {},
    )

    @Test
    fun `welcome offers both creating and restoring`() {
        compose.setContent { FirstRunContent(FirstRunUiState.Welcome, actions()) }

        compose.onNodeWithText("Create a new identity").assertExists()
        compose.onNodeWithText("I have a recovery phrase").assertExists()
    }

    @Test
    fun `creating an identity is reported to the caller`() {
        compose.setContent { FirstRunContent(FirstRunUiState.Welcome, actions()) }

        compose.onNodeWithText("Create a new identity").performClick()

        assertEquals(1, created)
    }

    @Test
    fun `every word of the phrase is shown with its position`() {
        // The numbers matter as much as the words — order is what makes a recovery phrase work.
        compose.setContent { FirstRunContent(FirstRunUiState.ShowPhrase(phrase), actions()) }

        phrase.forEachIndexed { index, word ->
            compose.onNodeWithText("${index + 1}. $word").assertExists()
        }
    }

    @Test
    fun `the phrase screen says plainly what losing it means`() {
        compose.setContent { FirstRunContent(FirstRunUiState.ShowPhrase(phrase), actions()) }

        compose.onNodeWithText("Anyone who has these words is you.").assertExists()
    }

    @Test
    fun `the phrase screen offers a way to copy it`() {
        // Not asserting on clipboard read-back: Android 10+ restricts clipboard reads to the
        // focused app/default IME, which Robolectric enforces too, making read-back unreliable
        // in this harness regardless of whether the write succeeded. The write path itself
        // (ClipboardManager.setPrimaryClip) is a single platform call with no branching to test.
        compose.setContent { FirstRunContent(FirstRunUiState.ShowPhrase(phrase), actions()) }

        compose.onNodeWithText("Copy to clipboard").assertExists()
        compose.onNodeWithText("Copy to clipboard").performClick()
    }

    @Test
    fun `verification cannot be submitted until every word is filled in`() {
        val state = FirstRunUiState.VerifyPhrase(positions = listOf(2, 9, 17), answers = listOf("", "", ""))
        compose.setContent { FirstRunContent(state, actions()) }

        compose.onNodeWithText("Confirm").assertIsNotEnabled()
    }

    @Test
    fun `verification submits once every word is filled in`() {
        val state = FirstRunUiState.VerifyPhrase(
            positions = listOf(2, 9, 17),
            answers = listOf("volume", "run", "repair"),
        )
        compose.setContent { FirstRunContent(state, actions()) }

        compose.onNodeWithText("Confirm").assertIsEnabled().performClick()

        assertEquals(1, verified)
    }

    @Test
    fun `verification asks for the words by their real position`() {
        val state = FirstRunUiState.VerifyPhrase(positions = listOf(2, 9, 17), answers = listOf("", "", ""))
        compose.setContent { FirstRunContent(state, actions()) }

        // Positions are zero-based internally but must be presented one-based.
        compose.onNodeWithText("Word 3").assertExists()
        compose.onNodeWithText("Word 10").assertExists()
        compose.onNodeWithText("Word 18").assertExists()
    }

    @Test
    fun `typing an answer reports the slot and the value`() {
        val state = FirstRunUiState.VerifyPhrase(positions = listOf(2, 9, 17), answers = listOf("", "", ""))
        compose.setContent { FirstRunContent(state, actions()) }

        compose.onNodeWithText("Word 10").performTextInput("run")

        assertEquals("run", answers[1])
    }

    @Test
    fun `a wrong answer says so without giving the word away`() {
        val state = FirstRunUiState.VerifyPhrase(
            positions = listOf(2),
            answers = listOf("wrong"),
            wrong = true,
        )
        compose.setContent { FirstRunContent(state, actions()) }

        compose.onNodeWithText("That doesn't match. Check your written copy rather than guessing.")
            .assertExists()

        // And it must not helpfully show the right answer, which would make the check
        // theatre rather than a check.
        assertTrue(
            "the expected word must not be on screen during verification",
            compose.onAllNodesWithText(phrase[2]).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `the username step explains that nobody owns a username`() {
        // The single most important thing to say here: nobody owns a username in this system.
        compose.setContent { FirstRunContent(FirstRunUiState.ChooseUsername(), actions()) }

        compose.onNodeWithText("What should people call you?").assertExists()
        compose.onNodeWithText("Nobody owns a username here", substring = true).assertExists()
    }

    @Test
    fun `a name can be skipped, because the key is the identity`() {
        compose.setContent { FirstRunContent(FirstRunUiState.ChooseUsername(), actions()) }

        compose.onNodeWithText("Skip for now").performClick()

        assertEquals(1, usernameSkipped)
    }

    @Test
    fun `an empty name cannot be submitted`() {
        compose.setContent { FirstRunContent(FirstRunUiState.ChooseUsername(), actions()) }
        compose.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `a rejected name is explained rather than silently dropped`() {
        val state = FirstRunUiState.ChooseUsername(
            username = "bad",
            error = "That name contains invisible characters.",
        )
        compose.setContent { FirstRunContent(state, actions()) }

        compose.onNodeWithText("That name contains invisible characters.").assertExists()
    }

    @Test
    fun `restoring also asks for a name, and says why`() {
        // Someone restoring reasonably expects everything back, and most of it comes back.
        // The name is the exception, so the screen explains rather than silently re-asking.
        val state = FirstRunUiState.ChooseUsername(restoring = true)
        compose.setContent { FirstRunContent(state, actions()) }

        compose.onNodeWithText("What should people call you?").assertExists()
        compose.onNodeWithText("carries your key, not your username", substring = true).assertExists()
    }

    @Test
    fun `restoring explains that skipping may bring the old name back`() {
        // True, and worth saying: peers hold the old profile signed by this key, and
        // latest-claim-wins restores it on the next sync.
        val state = FirstRunUiState.ChooseUsername(restoring = true)
        compose.setContent { FirstRunContent(state, actions()) }

        compose.onNodeWithText("your old username may return", substring = true).assertExists()
        compose.onNodeWithText("Skip for now").assertExists()
    }

    @Test
    fun `a fresh identity is not told about names coming back`() {
        // There is no old name to return, so that copy would be nonsense here.
        compose.setContent { FirstRunContent(FirstRunUiState.ChooseUsername(), actions()) }

        compose.onNodeWithText("your old username may return", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a restore error is shown to the user`() {
        val state = FirstRunUiState.Restore(input = "abandon abandon", error = "A recovery phrase is 24 words — this one has 2.")
        compose.setContent { FirstRunContent(state, actions()) }

        compose.onNodeWithText("A recovery phrase is 24 words — this one has 2.").assertExists()
    }
}
