package com.jonoshields.gossip.core.model

/** plan.md §3.4: `NICKNAME_MAX_CHARS`, counted in scalar values after NFC. */
const val NICKNAME_MAX_CHARS: Int = 32

/** Why a nickname was refused, in terms a settings screen can show. */
sealed interface NicknameProblem {
    data object Empty : NicknameProblem
    data class TooLong(val length: Int, val limit: Int) : NicknameProblem
    data object NotWellFormed : NicknameProblem
    data object HasPadding : NicknameProblem

    /**
     * Contains a control, format or bidi-override character.
     *
     * The one that matters is U+202E, right-to-left override, which reverses how the text
     * after it renders — `evil‮onoj` can display as something else entirely. It is the
     * "Trojan Source" trick, and a display name is exactly where someone would try it.
     */
    data object HasInvisibleCharacters : NicknameProblem
}

/**
 * Validation for a claimed nickname (plan.md §3.5).
 *
 * A nickname is a *claim*, never an identifier — nothing here makes it unique, and nothing
 * could. What this does is stop a name from lying about its own rendering.
 */
object Nickname {

    /** Returns the normalised nickname, or the reason it cannot be used. */
    fun validate(raw: String): Result<String> {
        if (!MessageText.isWellFormed(raw)) return failure(NicknameProblem.NotWellFormed)

        val normalised = MessageText.normalize(raw)
        // Whitespace-only counts as empty, not as padding: telling someone to trim a name
        // that would then be blank is not useful advice.
        if (normalised.trim().isEmpty()) return failure(NicknameProblem.Empty)
        if (normalised != normalised.trim()) return failure(NicknameProblem.HasPadding)
        if (normalised.any(::isInvisible)) return failure(NicknameProblem.HasInvisibleCharacters)

        val length = MessageText.countCodePoints(normalised)
        if (length > NICKNAME_MAX_CHARS) {
            return failure(NicknameProblem.TooLong(length, NICKNAME_MAX_CHARS))
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

    private fun failure(problem: NicknameProblem) =
        Result.failure<String>(InvalidNickname(problem))
}

class InvalidNickname(val problem: NicknameProblem) : IllegalArgumentException(problem.toString())
