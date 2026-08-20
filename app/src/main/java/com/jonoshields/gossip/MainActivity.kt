package com.jonoshields.gossip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.jonoshields.gossip.core.identity.IdentityState
import com.jonoshields.gossip.core.identity.IdentityStore
import com.jonoshields.gossip.theme.GossipTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var identity: IdentityStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read once at start-up: only a fully backed-up identity skips the first-run flow.
        // NeedsBackup deliberately does not, so closing the app mid-backup cannot be used
        // to slip past writing the phrase down.
        val ready = identity.state() is IdentityState.Ready

        enableEdgeToEdge()
        setContent {
            GossipTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    GossipApp(startWithIdentity = ready)
                }
            }
        }
    }
}
