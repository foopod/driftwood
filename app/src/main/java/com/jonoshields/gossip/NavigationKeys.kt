package com.jonoshields.gossip

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The message list. */
@Serializable data object Main : NavKey

/** Composing a new root, or a reply when [replyToRoot] is set. */
@Serializable
data class Compose(
    val replyToRoot: String? = null,
    val replyToParent: String? = null,
) : NavKey

/** One thread, addressed by its root id in hex. */
@Serializable data class Thread(val rootId: String) : NavKey

@Serializable data object Settings : NavKey

@Serializable data object Sync : NavKey

@Serializable data object ListenList : NavKey

/** One identity, addressed by its public key in hex. */
@Serializable data class Contact(val authorHex: String) : NavKey
