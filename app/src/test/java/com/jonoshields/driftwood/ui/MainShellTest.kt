package com.jonoshields.driftwood.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.jonoshields.driftwood.ui.shell.MainShellContent
import com.jonoshields.driftwood.ui.shell.MainTab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h3000dp")
class MainShellTest {

    @get:Rule val compose = createComposeRule()

    private fun show(activityBadge: Int = 0, onCompose: () -> Unit = {}) {
        compose.setContent {
            var tab by remember { mutableStateOf(MainTab.FEED) }
            MainShellContent(
                tab = tab,
                onTabChange = { tab = it },
                activityBadge = activityBadge,
                onSync = {},
                onSettings = {},
                onAddContact = {},
                onCompose = onCompose,
                onExpandSearch = {},
                feed = { Text("FEED PANE") },
                discover = { Text("DISCOVER PANE") },
                activity = { Text("ACTIVITY PANE") },
            )
        }
    }

    @Test
    fun `feed is the initial pane`() {
        show()

        compose.onNodeWithText("FEED PANE").assertExists()
        compose.onNodeWithText("ACTIVITY PANE").assertDoesNotExist()
    }

    @Test
    fun `tapping the Activity nav item switches to the Activity pane`() {
        show()

        compose.onNodeWithText("Activity").performClick()

        compose.onNodeWithText("ACTIVITY PANE").assertExists()
        compose.onNodeWithText("FEED PANE").assertDoesNotExist()
    }

    @Test
    fun `tapping the Discover nav item switches to the Discover pane`() {
        show()

        compose.onNodeWithText("Discover").performClick()

        compose.onNodeWithText("DISCOVER PANE").assertExists()
    }

    @Test
    fun `the Activity nav item carries a badge when there are unread replies`() {
        show(activityBadge = 3)

        compose.onNodeWithText("3", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `no badge is shown when there is nothing unread`() {
        show(activityBadge = 0)

        compose.onNodeWithText("0", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the compose FAB is present on Feed but not on Activity`() {
        show()

        compose.onNodeWithText("Compose", useUnmergedTree = true).assertExists()

        compose.onNodeWithText("Activity").performClick()

        compose.onNodeWithText("Compose", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a pane's scroll position survives switching away and back`() {
        lateinit var feedListState: LazyListState
        compose.setContent {
            var tab by remember { mutableStateOf(MainTab.FEED) }
            MainShellContent(
                tab = tab,
                onTabChange = { tab = it },
                activityBadge = 0,
                onSync = {}, onSettings = {}, onAddContact = {}, onCompose = {}, onExpandSearch = {},
                feed = { m ->
                    feedListState = rememberLazyListState()
                    LazyColumn(state = feedListState, modifier = m) {
                        // Tall rows so the list overflows the (very tall) test viewport and can scroll.
                        repeat(60) { i -> item { Text("row $i", Modifier.height(400.dp)) } }
                    }
                },
                discover = { Text("DISCOVER PANE") },
                activity = { Text("ACTIVITY PANE") },
            )
        }

        compose.runOnIdle { kotlinx.coroutines.runBlocking { feedListState.scrollToItem(30) } }
        compose.waitForIdle()
        // scrolled well past the top — row 0 is no longer composed
        compose.onNodeWithText("row 0").assertDoesNotExist()
        compose.onNodeWithText("row 31").assertExists()

        compose.onNodeWithText("Activity").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Feed").performClick()
        compose.waitForIdle()

        // still scrolled: the SaveableStateHolder kept the feed pane's LazyColumn position
        compose.onNodeWithText("row 0").assertDoesNotExist()
        compose.onNodeWithText("row 31").assertExists()
    }
}
