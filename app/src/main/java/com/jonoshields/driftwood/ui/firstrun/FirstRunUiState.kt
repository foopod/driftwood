package com.jonoshields.driftwood.ui.firstrun

/** Where the user is in getting an identity. The order is a requirement, not a preference. */
sealed interface FirstRunUiState {
    /** Deciding whether this is a new identity or a returning one. */
    data object Welcome : FirstRunUiState

    /** The phrase is on screen. This is the only time it is shown unprompted. */
    data class ShowPhrase(val words: List<String>) : FirstRunUiState

    /** Checking a sample; [positions] are indices into the phrase, [answers] aligned with them. */
    data class VerifyPhrase(
        val positions: List<Int>,
        val answers: List<String>,
        val wrong: Boolean = false,
    ) : FirstRunUiState {
        val canSubmit: Boolean get() = answers.all { it.isNotBlank() }
    }

    /** Choosing what to be called — always after the phrase is confirmed, never before. */
    data class ChooseUsername(
        val username: String = "",
        val error: String? = null,
        val saving: Boolean = false,
        /** Restoring rather than starting fresh — the phrase carries the key but not the name. */
        val restoring: Boolean = false,
    ) : FirstRunUiState {
        val canSubmit: Boolean get() = !saving && username.isNotBlank()
    }

    /** Typing a 24-word phrase back in to recover an existing identity. */
    data class Restore(val input: String = "", val error: String? = null) : FirstRunUiState

    data object Done : FirstRunUiState
}

sealed interface FirstRunEffect {
    data object Finished : FirstRunEffect
    data class Failed(val message: String) : FirstRunEffect
}
