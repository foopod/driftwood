package com.jonoshields.driftwood.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.driftwood.core.data.DirectoryRepository
import com.jonoshields.driftwood.core.data.MessageRepository
import com.jonoshields.driftwood.core.identity.IdentityState
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.core.store.EvictionReason
import com.jonoshields.driftwood.core.store.StorageConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val username: String? = null,
    val usernameDraft: String = "",
    val usernameError: String? = null,
    val usernameSaved: Boolean = false,
    val publicKey: String? = null,
    val messageCount: Int = 0,
    val windowDays: Long = 0,
    val budgetMegabytes: Long = 0,
    val budgetSplit: String = "",
    val lastPruneSummary: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: MessageRepository,
    private val directory: DirectoryRepository,
    private val identity: IdentityStore,
    private val config: StorageConfig,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            publicKey = when (val state = identity.state()) {
                is IdentityState.Ready -> state.author.toHex()
                is IdentityState.NeedsBackup -> state.author.toHex()
                IdentityState.None -> null
            },
            windowDays = config.windowMillis / (24 * 60 * 60 * 1000),
            budgetMegabytes = config.totalBudgetBytes / (1024 * 1024),
            budgetSplit = config.budgets().let { "${it.follow} / ${it.context} / ${it.gossip}" },
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { messages ->
                _uiState.update { it.copy(messageCount = messages.size) }
            }
        }
        viewModelScope.launch {
            val mine = directory.myProfile().getOrNull()
            _uiState.update {
                it.copy(username = mine?.username, usernameDraft = mine?.username.orEmpty())
            }
        }
    }

    fun updateUsername(value: String) {
        _uiState.update { it.copy(usernameDraft = value, usernameError = null, usernameSaved = false) }
    }

    /** Signs a fresh claim — names are mutable by design, latest claim wins. */
    fun saveUsername() {
        val draft = _uiState.value.usernameDraft
        if (draft.isBlank()) return
        viewModelScope.launch {
            directory.setMyUsername(draft)
                .onSuccess { profile ->
                    _uiState.update {
                        it.copy(username = profile.username, usernameSaved = true, usernameError = null)
                    }
                }
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(
                            usernameError = failure.cause?.message
                                ?: failure.message ?: "That name can't be used",
                        )
                    }
                }
        }
    }

    fun prune() {
        viewModelScope.launch {
            // Names age out on their own rules, so the same button runs both.
            directory.prune()
            repository.prune()
                .onSuccess { plan ->
                    val byReason = plan.evict.groupingBy { plan.reasons[it] }.eachCount()
                    _uiState.update {
                        it.copy(
                            lastPruneSummary = if (plan.evict.isEmpty()) {
                                "Nothing needed removing."
                            } else {
                                "Removed ${plan.evict.size}: " + byReason.entries.joinToString {
                                    "${it.value} ${describe(it.key)}"
                                }
                            },
                        )
                    }
                }
                .onFailure { failure ->
                    _uiState.update { it.copy(lastPruneSummary = "Pruning failed: ${failure.message}") }
                }
        }
    }

    private fun describe(reason: EvictionReason?): String = when (reason) {
        EvictionReason.BLOCKED -> "blocked"
        EvictionReason.OUT_OF_WINDOW -> "aged out"
        EvictionReason.OVER_FAIR_SHARE -> "over the cap"
        null -> "unknown"
    }
}
