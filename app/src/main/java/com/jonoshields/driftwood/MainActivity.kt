package com.jonoshields.driftwood

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.jonoshields.driftwood.core.identity.IdentityState
import com.jonoshields.driftwood.core.identity.IdentityStore
import com.jonoshields.driftwood.theme.DriftwoodTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var identity: IdentityStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only a fully backed-up identity skips first-run — NeedsBackup deliberately does not.
        val ready = identity.state() is IdentityState.Ready

        enableEdgeToEdge()
        setContent {
            DriftwoodTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DriftwoodApp(startWithIdentity = ready)
                }
            }
        }
    }
}
