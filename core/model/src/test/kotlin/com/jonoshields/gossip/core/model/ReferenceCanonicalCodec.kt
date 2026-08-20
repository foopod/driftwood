package com.jonoshields.gossip.core.model

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.Normalizer

/**
 * A second, deliberately independent implementation of the canonical preimage encoding.
 *
 * Written from the prose in plan.md §3.2, not by reading [CanonicalCodec]. The production
 * encoder writes fields into a single growing buffer; this one builds a list of
 * `(length, bytes)` pairs and concatenates at the end, computing every length separately.
 * The two agreeing is evidence about the *format*; two copies of the same code agreeing
 * would be evidence about nothing.
 *
 * Spec being implemented, restated from the plan so this file stands alone:
 *   - field order: v, author, root, parent, timestamp, text
 *   - each field: uint16 big-endian byte length, then that many bytes
 *   - v is 1 byte; timestamp is 8 bytes big-endian; absent root/parent encode as length 0
 *   - text is NFC-normalised UTF-8
 *   - id and sig are not in the preimage
 */
internal object ReferenceCanonicalCodec {

    fun encode(body: MessageBody): ByteArray {
        val fields = ArrayList<ByteArray>()

        fields += byteArrayOf(body.version.toByte())
        fields += body.author.toByteArray()
        fields += body.root?.toByteArray() ?: ByteArray(0)
        fields += body.parent?.toByteArray() ?: ByteArray(0)
        fields += longToBigEndian(body.timestampMillis)
        fields += Normalizer.normalize(body.text, Normalizer.Form.NFC).toByteArray(StandardCharsets.UTF_8)

        val out = ByteArrayOutputStream()
        for (field in fields) {
            val length = field.size
            check(length <= 0xFFFF) { "field longer than a uint16 length prefix can describe: $length" }
            out.write((length shr 8) and 0xFF)
            out.write(length and 0xFF)
            out.write(field)
        }
        return out.toByteArray()
    }

    private fun longToBigEndian(value: Long): ByteArray {
        val out = ByteArray(8)
        var remaining = value
        // Fill from the least significant end backwards, so this doesn't share any
        // shifting logic with the production writer.
        for (i in 7 downTo 0) {
            out[i] = (remaining and 0xFF).toByte()
            remaining = remaining ushr 8
        }
        return out
    }
}
