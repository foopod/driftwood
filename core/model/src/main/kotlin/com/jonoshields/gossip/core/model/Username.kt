package com.jonoshields.gossip.core.model

/** plan.md §3.4: `USERNAME_MAX_CHARS`, counted in scalar values after NFC. */
const val USERNAME_MAX_CHARS: Int = 32

/** Why a username was refused, in terms a settings screen can show. */
sealed interface UsernameProblem {
    data object Empty : UsernameProblem
    data class TooLong(val length: Int, val limit: Int) : UsernameProblem
    data object NotWellFormed : UsernameProblem
    data object HasPadding : UsernameProblem

    /**
     * Contains a control, format or bidi-override character.
     *
     * The one that matters is U+202E, right-to-left override, which reverses how the text
     * after it renders — `evil‮onoj` can display as something else entirely. It is the
     * "Trojan Source" trick, and a display name is exactly where someone would try it.
     */
    data object HasInvisibleCharacters : UsernameProblem
}

/**
 * Validation for a claimed username (plan.md §3.5).
 *
 * A username is a *claim*, never an identifier — nothing here makes it unique, and nothing
 * could. What this does is stop a name from lying about its own rendering.
 */
object Username {

    /** Returns the normalised username, or the reason it cannot be used. */
    fun validate(raw: String): Result<String> {
        if (!MessageText.isWellFormed(raw)) return failure(UsernameProblem.NotWellFormed)

        val normalised = MessageText.normalize(raw)
        // Whitespace-only counts as empty, not as padding: telling someone to trim a name
        // that would then be blank is not useful advice.
        if (normalised.trim().isEmpty()) return failure(UsernameProblem.Empty)
        if (normalised != normalised.trim()) return failure(UsernameProblem.HasPadding)
        if (normalised.any(::isInvisible)) return failure(UsernameProblem.HasInvisibleCharacters)

        val length = MessageText.countCodePoints(normalised)
        if (length > USERNAME_MAX_CHARS) {
            return failure(UsernameProblem.TooLong(length, USERNAME_MAX_CHARS))
        }
        return Result.success(normalised)
    }

    fun isValid(raw: String): Boolean = validate(raw).isSuccess

    private fun isInvisible(c: Char): Boolean = when (c.category) {
        // Cc: control. Cf: format, which is where the bidi overrides live. Zl/Zp: line and
        // paragraph separators. None of them belong in something rendered on one line.
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
