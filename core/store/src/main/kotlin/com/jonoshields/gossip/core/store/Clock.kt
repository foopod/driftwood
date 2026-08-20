package com.jonoshields.gossip.core.store

/**
 * Wall-clock time, injected rather than read from the system directly.
 *
 * `first_received_time` and every windowing decision depend on it, so tests need to be able
 * to place a message at a chosen moment without sleeping — and `android-testing` is explicit
 * that mixing a test clock with the wall clock is a flake source.
 */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }

        fun fixed(millis: Long): Clock = Clock { millis }
    }
}
