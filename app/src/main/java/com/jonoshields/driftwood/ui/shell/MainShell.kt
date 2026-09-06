package com.jonoshields.driftwood.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.driftwood.core.model.AuthorId
import com.jonoshields.driftwood.core.model.MessageId
import com.jonoshields.driftwood.ui.activity.ActivityContent
import com.jonoshields.driftwood.ui.activity.ActivityViewModel
import com.jonoshields.driftwood.ui.feed.FeedPane
import com.jonoshields.driftwood.ui.feed.FeedViewModel

enum class MainTab { FEED, ACTIVITY, DISCOVER }

/**
 * The shell around the three top-level destinations — Feed, Activity, Discover. Owns the one
 * scaffold (top bar, Compose FAB, bottom nav); each destination is a body-only pane. Only the
 * selected pane is composed, but [MainShellContent] keeps each pane's saved state (scroll
 * position) via a [rememberSaveableStateHolder] so switching tabs doesn't lose your place.
 */
@Composable
fun MainShell(
    onOpenThread: (MessageId) -> Unit,
    onOpenActivityThread: (root: MessageId, focus: MessageId) -> Unit,
    onOpenContact: (AuthorId) -> Unit,
    onCompose: () -> Unit,
    onSettings: () -> Unit,
    onSync: () -> Unit,
    onAddContact: () -> Unit,
    modifier: Modifier = Modifier,
    feedViewModel: FeedViewModel = hiltViewModel(),
    activityViewModel: ActivityViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.FEED) }

    val feedState by feedViewModel.uiState.collectAsStateWithLifecycle()
    val activityBadge by activityViewModel.unreadCount.collectAsStateWithLifecycle()
    val activityNames by activityViewModel.names.collectAsStateWithLifecycle()

    // Search is shared across the Feed and Discover panes; the state lives here.
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var selectedAuthor by remember { mutableStateOf<AuthorId?>(null) }

    fun collapseSearch() {
        searchExpanded = false
        searchText = ""
        selectedAuthor = null
        feedViewModel.setSearchText("")
        feedViewModel.clearAuthorFilter()
    }

    val onSearchTextChanged: (String) -> Unit = {
        searchText = it
        selectedAuthor = null
        feedViewModel.setSearchText(it)
    }
    val onAuthorSelected: (AuthorId) -> Unit = {
        selectedAuthor = it
        searchText = ""
        feedViewModel.selectAuthor(it)
    }
    val onAuthorCleared: () -> Unit = {
        selectedAuthor = null
        feedViewModel.clearAuthorFilter()
    }

    MainShellContent(
        tab = tab,
        onTabChange = { tab = it },
        activityBadge = activityBadge,
        onSync = onSync,
        onSettings = onSettings,
        onAddContact = onAddContact,
        onCompose = onCompose,
        onExpandSearch = { searchExpanded = true },
        modifier = modifier,
        feed = { paneModifier ->
            FeedPane(
                state = feedState,
                threads = feedViewModel.feed,
                emptyDefault = "Nothing from people you follow yet.",
                myAuthor = feedViewModel.myAuthor,
                searchExpanded = searchExpanded,
                searchText = searchText,
                selectedAuthor = selectedAuthor,
                onSearchTextChanged = onSearchTextChanged,
                onAuthorSelected = onAuthorSelected,
                onAuthorFilterCleared = onAuthorCleared,
                onSearchCollapsed = ::collapseSearch,
                onOpenThread = onOpenThread,
                onOpenContact = onOpenContact,
                onOpenOwnProfile = onSettings,
                onRefresh = onSync,
                modifier = paneModifier,
            )
        },
        discover = { paneModifier ->
            FeedPane(
                state = feedState,
                threads = feedViewModel.discover,
                emptyDefault = "Nothing incidental has turned up yet.",
                myAuthor = feedViewModel.myAuthor,
                searchExpanded = searchExpanded,
                searchText = searchText,
                selectedAuthor = selectedAuthor,
                onSearchTextChanged = onSearchTextChanged,
                onAuthorSelected = onAuthorSelected,
                onAuthorFilterCleared = onAuthorCleared,
                onSearchCollapsed = ::collapseSearch,
                onOpenThread = onOpenThread,
                onOpenContact = onOpenContact,
                onOpenOwnProfile = onSettings,
                onRefresh = onSync,
                modifier = paneModifier,
            )
        },
        activity = { paneModifier ->
            ActivityContent(
                items = activityViewModel.items,
                names = activityNames,
                unreadCount = activityBadge,
                myAuthor = activityViewModel.myAuthor,
                onOpenThread = onOpenActivityThread,
                onOpenContact = onOpenContact,
                modifier = paneModifier,
            )
        },
    )
}

/** Just the chrome: top bar, Compose FAB, bottom nav. Panes are passed as slots so this is
 * drivable from a test without Hilt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainShellContent(
    tab: MainTab,
    onTabChange: (MainTab) -> Unit,
    activityBadge: Int,
    onSync: () -> Unit,
    onSettings: () -> Unit,
    onAddContact: () -> Unit,
    onCompose: () -> Unit,
    onExpandSearch: () -> Unit,
    feed: @Composable (Modifier) -> Unit,
    discover: @Composable (Modifier) -> Unit,
    activity: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Held in the outer composition (not inside Scaffold's subcompose slot) so it stays stable,
    // and keeps each pane's saved state — its LazyColumn scroll position — across tab switches.
    val paneStateHolder = rememberSaveableStateHolder()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(tab.label()) },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = onSync,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Sync")
                        }
                        Box {
                            var menuOpen by remember { mutableStateOf(false) }
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (tab != MainTab.ACTIVITY) {
                                    DropdownMenuItem(
                                        text = { Text("Search") },
                                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                        onClick = { menuOpen = false; onExpandSearch() },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Quick verify") },
                                    onClick = { menuOpen = false; onAddContact() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = { menuOpen = false; onSettings() },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab != MainTab.ACTIVITY) {
                ExtendedFloatingActionButton(
                    onClick = onCompose,
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text("Compose") },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == MainTab.FEED,
                    onClick = { onTabChange(MainTab.FEED) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Message, contentDescription = null) },
                    label = { Text("Feed") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.ACTIVITY,
                    onClick = { onTabChange(MainTab.ACTIVITY) },
                    icon = {
                        BadgedBox(badge = { if (activityBadge > 0) Badge { Text(activityBadge.toString()) } }) {
                            Icon(Icons.Default.Notifications, contentDescription = null)
                        }
                    },
                    label = { Text("Activity") },
                )
                NavigationBarItem(
                    selected = tab == MainTab.DISCOVER,
                    onClick = { onTabChange(MainTab.DISCOVER) },
                    icon = { Icon(Icons.Default.Explore, contentDescription = null) },
                    label = { Text("Discover") },
                )
            }
        },
    ) { padding ->
        val paneModifier = Modifier.padding(padding)
        paneStateHolder.SaveableStateProvider(tab.name) {
            when (tab) {
                MainTab.FEED -> feed(paneModifier)
                MainTab.DISCOVER -> discover(paneModifier)
                MainTab.ACTIVITY -> activity(paneModifier)
            }
        }
    }
}

private fun MainTab.label() = when (this) {
    MainTab.FEED -> "Feed"
    MainTab.ACTIVITY -> "Activity"
    MainTab.DISCOVER -> "Discover"
}
