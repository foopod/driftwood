package com.jonoshields.gossip.core.store

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class RelativeTimeTest {

    private val now = 1_700_000_000_000L

    private fun ago(amount: Long, unit: TimeUnit) = now - unit.toMillis(amount)

    @Test
    fun `under a minute reads as just now`() {
        assertEquals("just now", RelativeTime.describe(now, now))
        assertEquals("just now", RelativeTime.describe(ago(59, TimeUnit.SECONDS), now))
    }

    @Test
    fun `a future timestamp also reads as just now, rather than negative`() {
        assertEquals("just now", RelativeTime.describe(now + 60_000, now))
    }

    @Test
    fun `minutes are singular and plural at the right boundary`() {
        assertEquals("1 minute ago", RelativeTime.describe(ago(1, TimeUnit.MINUTES), now))
        assertEquals("2 minutes ago", RelativeTime.describe(ago(2, TimeUnit.MINUTES), now))
        assertEquals("59 minutes ago", RelativeTime.describe(ago(59, TimeUnit.MINUTES), now))
    }

    @Test
    fun `an hour rolls minutes over into hours`() {
        assertEquals("1 hour ago", RelativeTime.describe(ago(60, TimeUnit.MINUTES), now))
        assertEquals("23 hours ago", RelativeTime.describe(ago(23, TimeUnit.HOURS), now))
    }

    @Test
    fun `a day rolls hours over into days`() {
        assertEquals("1 day ago", RelativeTime.describe(ago(24, TimeUnit.HOURS), now))
        assertEquals("6 days ago", RelativeTime.describe(ago(6, TimeUnit.DAYS), now))
    }

    @Test
    fun `a week rolls days over into weeks`() {
        assertEquals("1 week ago", RelativeTime.describe(ago(7, TimeUnit.DAYS), now))
        assertEquals("4 weeks ago", RelativeTime.describe(ago(29, TimeUnit.DAYS), now))
    }

    @Test
    fun `a month rolls weeks over into months`() {
        assertEquals("1 month ago", RelativeTime.describe(ago(30, TimeUnit.DAYS), now))
        assertEquals("12 months ago", RelativeTime.describe(ago(364, TimeUnit.DAYS), now))
    }

    @Test
    fun `a year rolls months over into years`() {
        assertEquals("1 year ago", RelativeTime.describe(ago(365, TimeUnit.DAYS), now))
        assertEquals("3 years ago", RelativeTime.describe(ago(365 * 3, TimeUnit.DAYS), now))
    }
}
