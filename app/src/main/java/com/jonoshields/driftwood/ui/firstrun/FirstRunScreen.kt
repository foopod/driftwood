package com.jonoshields.driftwood.ui.firstrun

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jonoshields.driftwood.core.model.USERNAME_MAX_CHARS
import com.jonoshields.driftwood.theme.DriftwoodTheme
import com.jonoshields.driftwood.ui.common.ProjectHeader

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
            onUsernameChange = viewModel::updateUsername,
            onSubmitUsername = viewModel::submitUsername,
            onSkipUsername = viewModel::skipUsername,
            onFinished = onFinished,
        ),
        modifier = modifier,
    )
}

/** Grouped so the content composable never depends on the ViewModel. */
data class FirstRunActions(
    val onCreate: () -> Unit,
    val onBeginRestore: () -> Unit,
    val onRestoreInputChange: (String) -> Unit,
    val onSubmitRestore: () -> Unit,
    val onBeginVerification: () -> Unit,
    val onVerificationAnswerChange: (Int, String) -> Unit,
    val onSubmitVerification: () -> Unit,
    val onShowPhraseAgain: () -> Unit,
    val onUsernameChange: (String) -> Unit,
    val onSubmitUsername: () -> Unit,
    val onSkipUsername: () -> Unit,
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
            is FirstRunUiState.ChooseUsername -> ChooseUsername(state, actions)
            is FirstRunUiState.Restore -> Restore(state, actions)
            FirstRunUiState.Done -> Done(actions)
        }
    }
}

@Composable
private fun Welcome(actions: FirstRunActions) {
    ProjectHeader()
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
            "on paper, or copy them into a password manager, and keep them somewhere safe.",
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

    val context = LocalContext.current
    OutlinedButton(
        onClick = { copyRecoveryPhrase(context, state.words) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Copy to clipboard")
    }

    Button(onClick = actions.onBeginVerification, modifier = Modifier.fillMaxWidth()) {
        Text("I've written it down")
    }
}

/** Marked sensitive (Android 13+, matching minSdk) so the system clipboard preview never shows these words in plain text. */
private fun copyRecoveryPhrase(context: Context, words: List<String>) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Recovery phrase", words.joinToString(" "))
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    clipboard.setPrimaryClip(clip)
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
private fun ChooseUsername(state: FirstRunUiState.ChooseUsername, actions: FirstRunActions) {
    Text("What should people call you?", style = MaterialTheme.typography.headlineMedium)
    if (state.restoring) {
        Text(
            "Your recovery phrase carries your key, not your username — so this is the " +
                "one thing it cannot bring back.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "If you skip it, your old username may return on its own: people who synced " +
                "with you still hold it, signed by your key, and it comes back the next " +
                "time you meet one of them. Setting a username here replaces it instead.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            "This travels with your messages so people can read who said what. Nobody " +
                "owns a username here — anyone can claim any username, and others will " +
                "always see it alongside a fingerprint derived from your key.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    OutlinedTextField(
        value = state.username,
        onValueChange = actions.onUsernameChange,
        label = { Text("Username") },
        singleLine = true,
        isError = state.error != null,
        supportingText = { Text("Up to $USERNAME_MAX_CHARS characters") },
        modifier = Modifier.fillMaxWidth(),
    )
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }

    Button(
        onClick = actions.onSubmitUsername,
        enabled = state.canSubmit,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Continue")
    }
    // Optional on purpose: the key is the identity, and it already exists by this point.
    TextButton(onClick = actions.onSkipUsername, modifier = Modifier.fillMaxWidth()) {
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
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun ShowPhrasePreview() {
    DriftwoodTheme {
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
    DriftwoodTheme { FirstRunContent(FirstRunUiState.Welcome, previewActions()) }
}

private fun previewActions() = FirstRunActions(
    onCreate = {}, onBeginRestore = {}, onRestoreInputChange = {}, onSubmitRestore = {},
    onBeginVerification = {}, onVerificationAnswerChange = { _, _ -> }, onSubmitVerification = {},
    onShowPhraseAgain = {}, onUsernameChange = {}, onSubmitUsername = {}, onSkipUsername = {},
    onFinished = {},
)
