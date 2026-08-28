package com.jonoshields.driftwood.ui.contacts

import com.jonoshields.driftwood.core.model.Ed25519Signer
import com.jonoshields.driftwood.core.store.NameResolver
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure logic, no Robolectric/repository needed — [sortContactEntries] is the one thing
 * actually deciding the Contacts screen's order. */
class SortContactEntriesTest {

    private fun author(seed: Int) = Ed25519Signer(ByteArray(32) { (it + seed).toByte() }).publicKey

    private fun entry(seed: Int, name: String, isFollowing: Boolean, verified: Boolean = false) =
        ContactEntry(
            author(seed),
            NameResolver.resolve(author(seed), nickname = name, username = null, confirmed = verified),
            isFollowing,
        )

    @Test
    fun `followed people sort before non-followed people, regardless of input order`() {
        val zebraFollowed = entry(1, "Zebra", isFollowing = true)
        val appleUnfollowed = entry(2, "Apple", isFollowing = false)

        val sorted = sortContactEntries(listOf(appleUnfollowed, zebraFollowed))

        assertEquals(listOf(zebraFollowed, appleUnfollowed), sorted)
    }

    @Test
    fun `within each group, entries sort alphabetically`() {
        val zebra = entry(1, "Zebra", isFollowing = true)
        val apple = entry(2, "Apple", isFollowing = true)
        val mango = entry(3, "Mango", isFollowing = true)

        val sorted = sortContactEntries(listOf(zebra, apple, mango))

        assertEquals(listOf(apple, mango, zebra), sorted)
    }

    @Test
    fun `a mix sorts as two alphabetical groups, followed first`() {
        val followedZebra = entry(1, "Zebra", isFollowing = true)
        val followedApple = entry(2, "Apple", isFollowing = true)
        val unfollowedMango = entry(3, "Mango", isFollowing = false)
        val unfollowedBanana = entry(4, "Banana", isFollowing = false)

        val sorted = sortContactEntries(listOf(unfollowedMango, followedZebra, unfollowedBanana, followedApple))

        assertEquals(listOf(followedApple, followedZebra, unfollowedBanana, unfollowedMango), sorted)
    }

    @Test
    fun `verified people sort before unverified, ahead of the follow grouping`() {
        val verifiedButUnfollowed = entry(1, "Zebra", isFollowing = false, verified = true)
        val unverifiedButFollowed = entry(2, "Apple", isFollowing = true, verified = false)

        val sorted = sortContactEntries(listOf(unverifiedButFollowed, verifiedButUnfollowed))

        assertEquals(listOf(verifiedButUnfollowed, unverifiedButFollowed), sorted)
    }

    @Test
    fun `within the verified group, followed still sorts first`() {
        val verifiedAndFollowed = entry(1, "Apple", isFollowing = true, verified = true)
        val verifiedOnly = entry(2, "Zebra", isFollowing = false, verified = true)

        val sorted = sortContactEntries(listOf(verifiedOnly, verifiedAndFollowed))

        assertEquals(listOf(verifiedAndFollowed, verifiedOnly), sorted)
    }
}
