package com.jonoshields.gossip.ui.contact

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.gossip.core.model.AuthorId
import com.jonoshields.gossip.ui.common.ContactActionsContent

@Composable
fun ContactScreen(
    author: AuthorId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactViewModel = hiltViewModel(),
) {
    LaunchedEffect(author) { viewModel.bind(author) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ContactContent(
        state = state,
        onBack = onBack,
        onSetNickname = viewModel::setNickname,
        onToggleListen = viewModel::toggleListen,
        onBlock = viewModel::block,
        onUnblock = viewModel::unblock,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactContent(
    state: ContactUiState,
    onBack: () -> Unit,
    onSetNickname: (String) -> Unit,
    onToggleListen: () -> Unit,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Contact") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        when (state) {
            ContactUiState.Loading -> Unit
            is ContactUiState.Loaded -> {
                ContactActionsContent(
                    displayName = state.displayName,
                    isListening = state.isListening,
                    isBlocked = state.isBlocked,
                    onSetNickname = onSetNickname,
                    onToggleListen = onToggleListen,
                    onBlock = onBlock,
                    onUnblock = onUnblock,
                    // No onClose: this is a real screen with its own Back button already.
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}
