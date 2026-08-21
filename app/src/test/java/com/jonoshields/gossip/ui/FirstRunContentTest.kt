package com.jonoshields.gossip.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.jonoshields.gossip.ui.firstrun.FirstRunActions
import com.jonoshields.gossip.ui.firstrun.FirstRunContent
import com.jonoshields.gossip.ui.firstrun.FirstRunUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behaviour of the first-run screens, driven by state and callbacks with no ViewModel and no
 * DI graph — the smallest shape that proves the contract (`android-testing`).
 *
 * These run locally under Robolectric because the device blocks adb input injection, and
 * because a test that needs a phone attached is a test that stops being run.
 */
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
    private var typedNickname = ""
    private var nicknameSubmitted = 0
    private var nicknameSkipped = 0

    private fun actions() = FirstRunActions(
        onCreate = { created++ },
        onBeginRestore = {},
        onRestoreInputChange = {},
        onSubmitRestore = {},
        onBeginVerification = {},
        onVerificationAnswerChange = { slot, value -> answers[slot] = value },
        onSubmitVerification = { verified++ },
        onShowPhraseAgain = {},
        onNicknameChange = { typedNickname = it },
        onSubmitNickname = { nicknameSubmitted++ },
        onSkipNickname = { nicknameSkipped++ },
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
        // Someone copying this onto paper needs the numbers as much as the words: order is
        // what makes the phrase work, and a phrase transcribed out of order is a lost
        // identity that looks fine until the day it matters.
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
    fun `the nickname step explains that a name is not a username`() {
        // The single most important thing to say here: nobody owns a name in this system.
        compose.setContent { FirstRunContent(FirstRunUiState.ChooseNickname(), actions()) }

        compose.onNodeWithText("What should people call you?").assertExists()
        compose.onNodeWithText("It is not a username", substring = true).assertExists()
    }

    @Test
    fun `a name can be skipped, because the key is the identity`() {
        compose.setContent { FirstRunContent(FirstRunUiState.ChooseNickname(), actions()) }

        compose.onNodeWithText("Skip for now").performClick()

        assertEquals(1, nicknameSkipped)
    }

    @Test
    fun `an empty name cannot be submitted`() {
        compose.setContent { FirstRunContent(FirstRunUiState.ChooseNickname(), actions()) }
        compose.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun `a rejected name is explained rather than silently dropped`() {
        val state = FirstRunUiState.ChooseNickname(
            nickname = "bad",
            error = "That name contains invisible characters.",
        )
        compose.setContent { FirstRunContent(state, actions()) }

        compose.onNodeWithText("That name contains invisible characters.").assertExists()
    }

    @Test
    fun `a restore error is shown to the user`() {
        val state = FirstRunUiState.Restore(input = "abandon abandon", error = "A recovery phrase is 24 words — this one has 2.")
        compose.setContent { FirstRunContent(state, actions()) }

        compose.onNodeWithText("A recovery phrase is 24 words — this one has 2.").assertExists()
    }
}
