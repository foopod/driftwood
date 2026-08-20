package com.jonoshields.gossip.core.model

import kotlin.random.Random

/**
 * Text samples that are already in NFC, so they can be fed straight to [MessageBody]
 * without tripping its normalisation invariant. Deliberately includes the awkward cases:
 * empty, astral-plane code points (surrogate pairs in UTF-16), CJK, RTL, and text sitting
 * exactly on the length boundary.
 */
private val NFC_TEXT_SAMPLES = listOf(
    "",
    "a",
    "hello world",
    "café", // composed é, already NFC
    "日本語のテキスト",
    "مرحبا بالعالم",
    "🜁🜂🜃🜄", // astral plane: 4 code points, 8 UTF-16 units
    "👩‍👩‍👧‍👦 family", // ZWJ sequence
    "line\nbreak\ttab",
    "a".repeat(MSG_MAX_CHARS), // exactly at the cap
    "🙂".repeat(MSG_MAX_CHARS), // at the cap in code points, double it in UTF-16 units
)

internal fun randomBody(random: Random): MessageBody {
    fun maybeId(): MessageId? =
        if (random.nextBoolean()) MessageId.of(random.nextBytes(32)) else null

    return MessageBody(
        author = AuthorId.of(random.nextBytes(32)),
        root = maybeId(),
        parent = maybeId(),
        // Includes 0 and the far future; negative is invalid and covered separately.
        timestampMillis = when (random.nextInt(10)) {
            0 -> 0L
            1 -> Long.MAX_VALUE
            else -> random.nextLong(0, 4_102_444_800_000L)
        },
        text = NFC_TEXT_SAMPLES.random(random),
    )
}

internal fun bodyWithText(text: String): MessageBody = MessageBody(
    author = AuthorId.of(ByteArray(32) { 0x42 }),
    root = null,
    parent = null,
    timestampMillis = 1_700_000_000_000L,
    text = text,
)
