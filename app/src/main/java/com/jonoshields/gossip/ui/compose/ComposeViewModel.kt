package com.jonoshields.gossip.ui.compose

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.gossip.core.data.MessageRepository
import com.jonoshields.gossip.core.model.MessageId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val repository: MessageRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    private val effects = Channel<ComposeEffect>(Channel.BUFFERED)
    val effect = effects.receiveAsFlow()

    private var replyToRoot: MessageId? = null
    private var replyToParent: MessageId? = null

    fun bind(root: MessageId?, parent: MessageId?) {
        replyToRoot = root
        replyToParent = parent

        if (root == null) {
            _uiState.update { it.copy(target = ReplyTarget.None) }
            return
        }

        viewModelScope.launch {
            // Quote the parent back so it is obvious what this reply attaches to. When the
            // parent is missing — pruned, or never held — the reply is still perfectly
            // valid; it just belongs to the thread rather than to a message.
            val quoted = parent?.let { repository.message(it).getOrNull() }
            _uiState.update {
                it.copy(
                    target = quoted?.let { message -> ReplyTarget.Message(message.body.text) }
                        ?: ReplyTarget.Thread
                )
            }
        }
    }

    fun updateText(text: String) {
        _uiState.update { it.copy(text = text, error = null) }
    }

    fun send() {
        val current = _uiState.value
        if (!current.canSend) return

        _uiState.update { it.copy(sending = true) }
        viewModelScope.launch {
            val root = replyToRoot
            val result = if (root == null) {
                repository.post(current.text)
            } else {
                repository.reply(root, replyToParent, current.text)
            }

            result
                .onSuccess {
                    _uiState.update { it.copy(text = "", sending = false) }
                    effects.trySend(ComposeEffect.Posted)
                }
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(sending = false, error = failure.message ?: "Could not post that")
                    }
                }
        }
    }
}
