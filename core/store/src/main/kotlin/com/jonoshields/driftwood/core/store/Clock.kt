package com.jonoshields.driftwood.core.store

/** Wall-clock time, injected rather than read directly, so tests can place a message at a chosen moment without sleeping. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }

        fun fixed(millis: Long): Clock = Clock { millis }
    }
}
