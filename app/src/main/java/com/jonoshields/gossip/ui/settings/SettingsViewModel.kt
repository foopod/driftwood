package com.jonoshields.gossip.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jonoshields.gossip.core.data.DirectoryRepository
import com.jonoshields.gossip.core.data.MessageRepository
import com.jonoshields.gossip.core.identity.IdentityState
import com.jonoshields.gossip.core.identity.IdentityStore
import com.jonoshields.gossip.core.store.Clock
import com.jonoshields.gossip.core.store.EvictionReason
import com.jonoshields.gossip.core.store.StorageConfig
import com.jonoshields.gossip.core.sync.SyncStore
import com.jonoshields.gossip.sync.DebugSync
import com.jonoshields.gossip.sync.SyncSummaryText
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
    val debugSyncRunning: Boolean = false,
    val debugSyncSummary: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: MessageRepository,
    private val directory: DirectoryRepository,
    private val identity: IdentityStore,
    private val config: StorageConfig,
    private val syncStore: SyncStore,
    private val clock: Clock,
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
            budgetSplit = config.budgets().let { "${it.listen} / ${it.context} / ${it.gossip}" },
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

    /**
     * Signs a fresh claim. Names are mutable by design — latest claim wins (plan.md §3.5) —
     * so this is the ordinary path, not an exceptional one.
     */
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

    /**
     * Runs a real sync session against an in-process synthetic peer (M2 plan, step 6).
     * There is no real transport yet, so this is the only way to see the protocol — real
     * framing, reconciliation, verification and ingest — actually move content into the
     * Room store before M3a exists.
     */
    fun syncWithDebugPeer() {
        if (_uiState.value.debugSyncRunning) return
        viewModelScope.launch {
            _uiState.update { it.copy(debugSyncRunning = true, debugSyncSummary = null) }
            val result = DebugSync.run(syncStore, identity.publicKey(), clock)
            _uiState.update {
                it.copy(debugSyncRunning = false, debugSyncSummary = SyncSummaryText.describe(result))
            }
        }
    }
}
