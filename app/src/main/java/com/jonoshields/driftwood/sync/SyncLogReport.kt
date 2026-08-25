package com.jonoshields.driftwood.sync

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Where a "send log" opens to — the one dev address, not a user preference. */
private const val LOG_RECIPIENT = "jonathonshields@gmail.com"

/**
 * Writes the current [SyncLog] snapshot to a cache file and opens the share sheet pre-addressed
 * as an email with it attached. Exists because the Xiaomi test device rejects `adb` outright, so
 * an in-app export is the only way to get sync diagnostics off it.
 */
object SyncLogReport {
    fun send(context: Context, log: String) {
        val dir = File(context.cacheDir, "sync-logs").apply { mkdirs() }
        val file = File(dir, "sync-log.txt")
        file.writeText(log)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(LOG_RECIPIENT))
            putExtra(Intent.EXTRA_SUBJECT, "Driftwood sync log")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Send sync log"))
    }
}
