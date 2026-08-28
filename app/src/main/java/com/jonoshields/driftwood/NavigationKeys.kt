package com.jonoshields.driftwood

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The message list. */
@Serializable data object Main : NavKey

/** Composing a new root, or a reply when [replyToRoot] is set. [introMode] just swaps placeholder copy for the guided first post. */
@Serializable
data class Compose(
    val replyToRoot: String? = null,
    val replyToParent: String? = null,
    val introMode: Boolean = false,
) : NavKey

/** One thread, addressed by its root id in hex. */
@Serializable data class Thread(val rootId: String) : NavKey

@Serializable data object Settings : NavKey

@Serializable data object Sync : NavKey

/** The merged list of everyone confirmed, plus everyone followed. */
@Serializable data object Contacts : NavKey

@Serializable data object Blocklist : NavKey

@Serializable data object AddContact : NavKey

/** One identity, addressed by its public key in hex. */
@Serializable data class Contact(val authorHex: String) : NavKey
