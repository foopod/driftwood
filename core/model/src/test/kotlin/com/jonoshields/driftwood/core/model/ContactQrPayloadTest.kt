package com.jonoshields.driftwood.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactQrPayloadTest {

    private val author = AuthorId.of(ByteArray(32) { it.toByte() })

    @Test
    fun `encode then decode round-trips to the same author`() {
        assertEquals(author, ContactQrPayload.decode(ContactQrPayload.encode(author)))
    }

    @Test
    fun `a payload with no gossip prefix is not a contact code`() {
        assertNull(ContactQrPayload.decode(author.toHex()))
        assertNull(ContactQrPayload.decode("https://example.com"))
    }

    @Test
    fun `a gossip-prefixed payload with malformed hex is not a contact code`() {
        assertNull(ContactQrPayload.decode("gossip:not-hex-at-all"))
        assertNull(ContactQrPayload.decode("gossip:"))
    }

    @Test
    fun `encoding carries the gossip prefix`() {
        assertEquals("gossip:" + author.toHex(), ContactQrPayload.encode(author))
    }
}
