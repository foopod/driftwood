package com.jonoshields.gossip.ui.firstrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.gossip.core.model.NICKNAME_MAX_CHARS
import com.jonoshields.gossip.theme.GossipTheme

@Composable
fun FirstRunScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FirstRunViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    FirstRunContent(
        state = state,
        actions = FirstRunActions(
            onCreate = viewModel::createIdentity,
            onBeginRestore = viewModel::beginRestore,
            onRestoreInputChange = viewModel::updateRestoreInput,
            onSubmitRestore = viewModel::submitRestore,
            onBeginVerification = viewModel::beginVerification,
            onVerificationAnswerChange = viewModel::updateVerificationAnswer,
            onSubmitVerification = viewModel::submitVerification,
            onShowPhraseAgain = viewModel::showPhraseAgain,
            onNicknameChange = viewModel::updateNickname,
            onSubmitNickname = viewModel::submitNickname,
            onSkipNickname = viewModel::skipNickname,
            onFinished = onFinished,
        ),
        modifier = modifier,
    )
}

/** Grouped so the content composable never depends on the ViewModel (android-dev). */
data class FirstRunActions(
    val onCreate: () -> Unit,
    val onBeginRestore: () -> Unit,
    val onRestoreInputChange: (String) -> Unit,
    val onSubmitRestore: () -> Unit,
    val onBeginVerification: () -> Unit,
    val onVerificationAnswerChange: (Int, String) -> Unit,
    val onSubmitVerification: () -> Unit,
    val onShowPhraseAgain: () -> Unit,
    val onNicknameChange: (String) -> Unit,
    val onSubmitNickname: () -> Unit,
    val onSkipNickname: () -> Unit,
    val onFinished: () -> Unit,
)

@Composable
internal fun FirstRunContent(
    state: FirstRunUiState,
    actions: FirstRunActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (state) {
            FirstRunUiState.Welcome -> Welcome(actions)
            is FirstRunUiState.ShowPhrase -> ShowPhrase(state, actions)
            is FirstRunUiState.VerifyPhrase -> VerifyPhrase(state, actions)
            is FirstRunUiState.ChooseNickname -> ChooseNickname(state, actions)
            is FirstRunUiState.Restore -> Restore(state, actions)
            FirstRunUiState.Done -> Done(actions)
        }
    }
}

@Composable
private fun Welcome(actions: FirstRunActions) {
    Text("Gossip", style = MaterialTheme.typography.headlineLarge)
    Text(
        "There are no accounts and no servers here. Your identity is a key that lives " +
            "only on this phone, and messages travel when you meet someone and sync.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(Modifier.height(8.dp))
    Button(onClick = actions.onCreate, modifier = Modifier.fillMaxWidth()) {
        Text("Create a new identity")
    }
    OutlinedButton(onClick = actions.onBeginRestore, modifier = Modifier.fillMaxWidth()) {
        Text("I have a recovery phrase")
    }
}

@Composable
private fun ShowPhrase(state: FirstRunUiState.ShowPhrase, actions: FirstRunActions) {
    Text("Your recovery phrase", style = MaterialTheme.typography.headlineMedium)
    Text(
        "These 24 words are the only way back to this identity if you lose this phone. " +
            "Nothing else can recover it — not us, not another device. Write them down " +
            "on paper and keep them somewhere safe.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "Anyone who has these words is you.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.words.chunked(2).forEachIndexed { rowIndex, pair ->
                Row(Modifier.fillMaxWidth()) {
                    pair.forEachIndexed { columnIndex, word ->
                        val number = rowIndex * 2 + columnIndex + 1
                        Text(
                            text = "$number. $word",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    Button(onClick = actions.onBeginVerification, modifier = Modifier.fillMaxWidth()) {
        Text("I've written it down")
    }
}

@Composable
private fun VerifyPhrase(state: FirstRunUiState.VerifyPhrase, actions: FirstRunActions) {
    Text("Check your copy", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Type these words from what you wrote down. This is the last moment a mistake " +
            "can still be fixed.",
        style = MaterialTheme.typography.bodyMedium,
    )

    state.positions.forEachIndexed { slot, position ->
        OutlinedTextField(
            value = state.answers[slot],
            onValueChange = { actions.onVerificationAnswerChange(slot, it) },
            label = { Text("Word ${position + 1}") },
            singleLine = true,
            isError = state.wrong,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (state.wrong) {
        Text(
            "That doesn't match. Check your written copy rather than guessing.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Button(
        onClick = actions.onSubmitVerification,
        enabled = state.canSubmit,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Confirm")
    }
    TextButton(onClick = actions.onShowPhraseAgain, modifier = Modifier.fillMaxWidth()) {
        Text("Show me the phrase again")
    }
}

@Composable
private fun ChooseNickname(state: FirstRunUiState.ChooseNickname, actions: FirstRunActions) {
    Text("What should people call you?", style = MaterialTheme.typography.headlineMedium)
    Text(
        "This travels with your messages so people can read who said what. It is not a " +
            "username — nobody owns a name here, anyone can claim any name, and others " +
            "will always see it alongside a short code derived from your key.",
        style = MaterialTheme.typography.bodyMedium,
    )

    OutlinedTextField(
        value = state.nickname,
        onValueChange = actions.onNicknameChange,
        label = { Text("Name") },
        singleLine = true,
        isError = state.error != null,
        supportingText = { Text("Up to $NICKNAME_MAX_CHARS characters") },
        modifier = Modifier.fillMaxWidth(),
    )
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }

    Button(
        onClick = actions.onSubmitNickname,
        enabled = state.canSubmit,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Continue")
    }
    // Optional on purpose: the key is the identity, and it already exists by this point.
    TextButton(onClick = actions.onSkipNickname, modifier = Modifier.fillMaxWidth()) {
        Text("Skip for now")
    }
}

@Composable
private fun Restore(state: FirstRunUiState.Restore, actions: FirstRunActions) {
    Text("Restore your identity", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Type all 24 words, separated by spaces. Order matters.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = state.input,
        onValueChange = actions.onRestoreInputChange,
        label = { Text("Recovery phrase") },
        isError = state.error != null,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        minLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
    Button(onClick = actions.onSubmitRestore, modifier = Modifier.fillMaxWidth()) {
        Text("Restore")
    }
}

@Composable
private fun Done(actions: FirstRunActions) {
    Text("You're set up", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Your network is quiet — post something, then sync with someone to begin.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Start,
    )
    Button(onClick = actions.onFinished, modifier = Modifier.fillMaxWidth()) {
        Text("Start writing")
    }
    Spacer(Modifier.width(0.dp).height(0.dp))
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun ShowPhrasePreview() {
    GossipTheme {
        FirstRunContent(
            state = FirstRunUiState.ShowPhrase(
                listOf(
                    "ripple", "kitten", "anchor", "desert", "lunar", "pledge",
                    "fabric", "velvet", "tumble", "gossip", "obscure", "hollow",
                    "orbit", "shrug", "miracle", "return", "arrow", "timber",
                    "cousin", "puzzle", "sight", "fever", "crumble", "wisdom",
                )
            ),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WelcomePreview() {
    GossipTheme { FirstRunContent(FirstRunUiState.Welcome, previewActions()) }
}

private fun previewActions() = FirstRunActions(
    onCreate = {}, onBeginRestore = {}, onRestoreInputChange = {}, onSubmitRestore = {},
    onBeginVerification = {}, onVerificationAnswerChange = { _, _ -> }, onSubmitVerification = {},
    onShowPhraseAgain = {}, onNicknameChange = {}, onSubmitNickname = {}, onSkipNickname = {},
    onFinished = {},
)
