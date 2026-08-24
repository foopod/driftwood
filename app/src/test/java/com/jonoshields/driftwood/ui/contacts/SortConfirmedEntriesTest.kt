package com.jonoshields.driftwood.ui.contacts

import com.jonoshields.driftwood.core.model.Ed25519Signer
import com.jonoshields.driftwood.core.store.NameResolver
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure logic, no Robolectric/repository needed — [sortConfirmedEntries] is the one thing
 * actually deciding the merged Contacts screen's order. */
class SortConfirmedEntriesTest {

    private fun author(seed: Int) = Ed25519Signer(ByteArray(32) { (it + seed).toByte() }).publicKey

    private fun entry(seed: Int, name: String, isListening: Boolean) =
        ConfirmedEntry(author(seed), NameResolver.resolve(author(seed), nickname = name, username = null), isListening)

    @Test
    fun `listened people sort before confirmed-only people, regardless of input order`() {
        val zebraListened = entry(1, "Zebra", isListening = true)
        val appleConfirmedOnly = entry(2, "Apple", isListening = false)

        val sorted = sortConfirmedEntries(listOf(appleConfirmedOnly, zebraListened))

        assertEquals(listOf(zebraListened, appleConfirmedOnly), sorted)
    }

    @Test
    fun `within each group, entries sort alphabetically`() {
        val zebra = entry(1, "Zebra", isListening = true)
        val apple = entry(2, "Apple", isListening = true)
        val mango = entry(3, "Mango", isListening = true)

        val sorted = sortConfirmedEntries(listOf(zebra, apple, mango))

        assertEquals(listOf(apple, mango, zebra), sorted)
    }

    @Test
    fun `a mix sorts as two alphabetical groups, listened first`() {
        val listenedZebra = entry(1, "Zebra", isListening = true)
        val listenedApple = entry(2, "Apple", isListening = true)
        val confirmedOnlyMango = entry(3, "Mango", isListening = false)
        val confirmedOnlyBanana = entry(4, "Banana", isListening = false)

        val sorted = sortConfirmedEntries(listOf(confirmedOnlyMango, listenedZebra, confirmedOnlyBanana, listenedApple))

        assertEquals(listOf(listenedApple, listenedZebra, confirmedOnlyBanana, confirmedOnlyMango), sorted)
    }
}
