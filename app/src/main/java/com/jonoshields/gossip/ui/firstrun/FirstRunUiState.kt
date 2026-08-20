package com.jonoshields.gossip.ui.firstrun

/**
 * Where the user is in getting an identity. The order is a requirement, not a preference:
 * an identity that has never been written down is one dropped phone away from being gone
 * forever, and the re-entry step is the only moment a transcription error is still fixable
 * (plan.md §6, §9).
 */
sealed interface FirstRunUiState {
    /** Deciding whether this is a new identity or a returning one. */
    data object Welcome : FirstRunUiState

    /** The phrase is on screen. This is the only time it is shown unprompted. */
    data class ShowPhrase(val words: List<String>) : FirstRunUiState

    /**
     * Checking a sample of the phrase. [positions] are zero-based indices into the phrase;
     * [answers] is what has been typed so far, aligned with them.
     */
    data class VerifyPhrase(
        val positions: List<Int>,
        val answers: List<String>,
        val wrong: Boolean = false,
    ) : FirstRunUiState {
        val canSubmit: Boolean get() = answers.all { it.isNotBlank() }
    }

    /** Typing a 24-word phrase back in to recover an existing identity. */
    data class Restore(val input: String = "", val error: String? = null) : FirstRunUiState

    data object Done : FirstRunUiState
}

sealed interface FirstRunEffect {
    data object Finished : FirstRunEffect
    data class Failed(val message: String) : FirstRunEffect
}
