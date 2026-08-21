package com.jonoshields.gossip.ui.firstrun

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.gossip.core.data.DirectoryRepository
import com.jonoshields.gossip.core.identity.IdentityState
import com.jonoshields.gossip.core.identity.IdentityStore
import com.jonoshields.gossip.core.identity.PhraseProblem
import com.jonoshields.gossip.core.identity.RestoreResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class FirstRunViewModel @Inject constructor(
    private val identity: IdentityStore,
    private val directory: DirectoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<FirstRunUiState>(initialState())
    val uiState: StateFlow<FirstRunUiState> = _uiState.asStateFlow()

    // Channel rather than SharedFlow so an effect emitted while backgrounded buffers and
    // replays instead of being dropped (android-dev).
    private val effects = Channel<FirstRunEffect>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()

    private fun initialState(): FirstRunUiState =
        when (identity.state()) {
            // An identity that exists but was never written down resumes at the phrase,
            // rather than letting the user slip past the backup step by restarting the app.
            is IdentityState.NeedsBackup -> FirstRunUiState.ShowPhrase(identity.recoveryPhrase())
            is IdentityState.Ready -> FirstRunUiState.Done
            IdentityState.None -> FirstRunUiState.Welcome
        }

    fun createIdentity() {
        runGuarded {
            if (identity.state() == IdentityState.None) identity.create()
            _uiState.value = FirstRunUiState.ShowPhrase(identity.recoveryPhrase())
        }
    }

    fun beginRestore() {
        _uiState.value = FirstRunUiState.Restore()
    }

    fun updateRestoreInput(text: String) {
        val current = _uiState.value
        if (current is FirstRunUiState.Restore) {
            _uiState.value = current.copy(input = text, error = null)
        }
    }

    fun submitRestore() {
        val current = _uiState.value as? FirstRunUiState.Restore ?: return
        runGuarded {
            when (val result = identity.restore(current.input)) {
                // A restored identity has a key but no name: the phrase carries the seed
                // and nothing else.
                is RestoreResult.Success ->
                    _uiState.value = FirstRunUiState.ChooseNickname(restoring = true)
                RestoreResult.AlreadyExists ->
                    _uiState.value = current.copy(
                        error = "This device already has an identity. Restoring would replace it."
                    )
                is RestoreResult.InvalidPhrase ->
                    _uiState.value = current.copy(error = describe(result.problem))
            }
        }
    }

    fun beginVerification() {
        val phrase = (_uiState.value as? FirstRunUiState.ShowPhrase)?.words ?: return
        // Three positions, sampled once so they don't shuffle under the user mid-answer.
        val positions = phrase.indices.shuffled(Random.Default).take(VERIFY_WORD_COUNT).sorted()
        _uiState.value = FirstRunUiState.VerifyPhrase(positions, List(positions.size) { "" })
    }

    fun updateVerificationAnswer(index: Int, value: String) {
        val current = _uiState.value as? FirstRunUiState.VerifyPhrase ?: return
        _uiState.value = current.copy(
            answers = current.answers.toMutableList().also { it[index] = value },
            wrong = false,
        )
    }

    fun submitVerification() {
        val current = _uiState.value as? FirstRunUiState.VerifyPhrase ?: return
        runGuarded {
            val phrase = identity.recoveryPhrase()
            val correct = current.positions.withIndex().all { (slot, position) ->
                current.answers[slot].trim().lowercase() == phrase[position]
            }
            if (correct) {
                identity.confirmBackedUp()
                _uiState.value = FirstRunUiState.ChooseNickname()
            } else {
                _uiState.value = current.copy(wrong = true)
            }
        }
    }

    fun updateNickname(value: String) {
        val current = _uiState.value as? FirstRunUiState.ChooseNickname ?: return
        _uiState.value = current.copy(nickname = value, error = null)
    }

    fun submitNickname() {
        val current = _uiState.value as? FirstRunUiState.ChooseNickname ?: return
        if (!current.canSubmit) return

        _uiState.value = current.copy(saving = true)
        viewModelScope.launch {
            directory.setMyNickname(current.nickname)
                .onSuccess { finish() }
                .onFailure { failure ->
                    _uiState.value = current.copy(
                        saving = false,
                        error = failure.cause?.message ?: failure.message ?: "That name can't be used",
                    )
                }
        }
    }

    /** A name is optional — the key is the identity, and it already exists by this point. */
    fun skipNickname() = finish()

    /** Back to the phrase, for someone who realises mid-check that they mistyped it. */
    fun showPhraseAgain() {
        runGuarded { _uiState.value = FirstRunUiState.ShowPhrase(identity.recoveryPhrase()) }
    }

    private fun finish() {
        _uiState.value = FirstRunUiState.Done
        effects.trySend(FirstRunEffect.Finished)
    }

    private fun describe(problem: PhraseProblem): String = when (problem) {
        is PhraseProblem.WrongWordCount ->
            "A recovery phrase is ${problem.expected} words — this one has ${problem.actual}."
        is PhraseProblem.UnknownWord ->
            "Word ${problem.position + 1} (\"${problem.word}\") isn't in the word list."
        PhraseProblem.DoesNotCheckOut ->
            "That phrase doesn't check out — a word is probably wrong or out of order."
    }

    private fun runGuarded(block: () -> Unit) {
        viewModelScope.launch {
            runCatching(block).onFailure {
                effects.trySend(FirstRunEffect.Failed(it.message ?: "Something went wrong"))
            }
        }
    }

    private companion object {
        const val VERIFY_WORD_COUNT = 3
    }
}
