package com.jonoshields.driftwood.sync

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression coverage for the leaked-listener bug: when a listen attempt's Wi-Fi Direct racer
 * loses the race (or the attempt is cancelled), its [WifiDirectTransport] call must actually be
 * cancelled — not just have its side effects torn down — or its `BroadcastReceiver` stays
 * registered forever and reacts to the *next* sync's group-formation broadcast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WifiDirectTransportTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val transport = WifiDirectTransport(context, WifiDirectChannel(context), SyncLog())

    @Test(timeout = 15_000)
    fun `cancelling awaitIncomingConnection unregisters its broadcast receiver`() = runBlocking {
        val before = shadowOf(context).registeredReceivers.size

        val racer = launch(Dispatchers.IO) {
            runCatching { transport.awaitIncomingConnection() }
        }

        // awaitStaleGroupRemoved's requestGroupInfo callback is delivered on the (paused) main
        // looper; idle it from this thread until the racer has registered its receiver.
        withTimeout(10_000) {
            while (shadowOf(context).registeredReceivers.size == before) {
                shadowOf(Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(before + 1, shadowOf(context).registeredReceivers.size)

        racer.cancel()
        racer.join()

        assertEquals(before, shadowOf(context).registeredReceivers.size)
    }
}
