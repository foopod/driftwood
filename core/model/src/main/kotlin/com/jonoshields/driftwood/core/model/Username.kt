package com.jonoshields.driftwood.core.model

/** Max username length in scalar values after NFC normalization. */
const val USERNAME_MAX_CHARS: Int = 32

/** Why a username was refused, in terms a settings screen can show. */
sealed interface UsernameProblem {
    data object Empty : UsernameProblem
    data class TooLong(val length: Int, val limit: Int) : UsernameProblem
    data object NotWellFormed : UsernameProblem
    data object HasPadding : UsernameProblem

    /** Contains a control, format, or bidi-override character (the "Trojan Source" trick). */
    data object HasInvisibleCharacters : UsernameProblem
}

/** A username is a claim, not an identifier — nothing here makes it unique. */
object Username {

    /** Returns the normalised username, or the reason it cannot be used. */
    fun validate(raw: String): Result<String> {
        if (!MessageText.isWellFormed(raw)) return failure(UsernameProblem.NotWellFormed)

        val normalised = MessageText.normalize(raw)
        // Whitespace-only counts as empty, not padding, since trimming it would leave nothing.
        if (normalised.trim().isEmpty()) return failure(UsernameProblem.Empty)
        if (normalised != normalised.trim()) return failure(UsernameProblem.HasPadding)
        if (normalised.any(::isInvisible)) return failure(UsernameProblem.HasInvisibleCharacters)

        val length = MessageText.countCodePoints(normalised)
        if (length > USERNAME_MAX_CHARS) {
            return failure(UsernameProblem.TooLong(length, USERNAME_MAX_CHARS))
        }
        return Result.success(normalised)
    }

    /** Control, format, or line/paragraph separator characters — none belong in a one-line name. */
    private fun isInvisible(c: Char): Boolean = when (c.category) {
        CharCategory.CONTROL,
        CharCategory.FORMAT,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR,
        -> true
        else -> false
    }

    private fun failure(problem: UsernameProblem) =
        Result.failure<String>(InvalidUsername(problem))
}

class InvalidUsername(val problem: UsernameProblem) : IllegalArgumentException(problem.toString())
