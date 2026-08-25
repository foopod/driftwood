package com.jonoshields.driftwood.sync

import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** What happened during the current (or most recent) listen/connect attempt, for the "send log" report. Cleared at the start of every attempt, not appended to across attempts. */
@Singleton
class SyncLog @Inject constructor() {
    private val lines = mutableListOf<String>()
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun start() {
        lines.clear()
        event("session started")
    }

    @Synchronized
    fun event(message: String) {
        lines += "${timestampFormat.format(System.currentTimeMillis())}  $message"
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")
}
