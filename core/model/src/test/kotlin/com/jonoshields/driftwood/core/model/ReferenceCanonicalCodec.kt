package com.jonoshields.driftwood.core.model

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.Normalizer

/** A second, independent implementation of the canonical preimage encoding (v, author, root, parent, timestamp, text — each length-prefixed with uint16 BE, id/sig excluded), built without reading [CanonicalCodec]. */
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
