# Local deletion of your own posts

Expands README item 5 ("Planned, not yet implemented"). Lets you remove one of your own past
messages from your own device — **scoped to messages that have never been transmitted to a
peer.** Once a message has been sent even once, deletion is no longer offered for it at all.

## Why scope to never-transmitted

The alternative — deleting content that already propagated, with a tombstone to stop it bouncing
back on a future sync — was drafted first and rejected. Two reasons:

1. **It can't be made honest.** "Deletes your copy; can't touch anyone else's" is true, but a
   button that behaves identically whether the post reached zero people or a hundred, with the
   real limit buried in a confirmation dialog's fine print, doesn't actually read as honest to
   the person tapping it — it reads as delete. This app is otherwise careful to state limits
   plainly rather than dress them up (the blocklist's own copy already does this: it says outright
   that it can't reach a device it already synced to). Scoping to never-sent sidesteps the problem
   entirely instead of disclosing around it: the option is simply *there* before your first sync
   of that content and *gone* after — nothing to caveat, because there's nothing partial about
   what it does.
2. **It's a much bigger feature than it needs to be.** A permanent tombstone table means a new
   `SyncStore` method (`core/sync/.../SyncStore.kt:29`), a new ingest-time drop path in
   `Session.kt` next to the existing blocklist check, a new counter threaded through
   `PhaseOutcome`/`SyncSummary`, and a lasting new surface for `core/sync` to reason about
   forever. None of that is needed if deletion never has to interact with anything already
   offered to or received from a peer.

The real, if unglamorous, trade-off: once you've synced even once, "delete" stops existing for
that post — including for a typo you notice five minutes later if a sync happened to land in
between. That's deliberate, not a gap: this feature is "undo before it goes out," not "delete my
post." For everything after your first sync, [[feature-local-hiding]] is the fallback — hide
covers "I don't want to see this anymore," which is a claim this app can actually keep.

## Data model: one boolean column, no new table

```kotlin
// on MessageEntity, Entities.kt:53
@ColumnInfo(name = "unsent") val unsent: Boolean?,
```

- Set `true` when a message you author is first written locally (`ComposeViewModel`'s send path).
- Set to `null` the moment it's actually served to a peer — see below.
- Anything that arrives via sync ingest (not authored on this device just now) is never `true`;
  it's simply not a candidate for this feature at all, so the column is `null` for it by default.

No timestamp, no separate table, nothing to prune or age out. A per-message boolean is the entire
mechanism: `unsent = true` means "eligible," `null` means "not eligible," and eligibility only
ever moves one direction, exactly once, per message.

**Where it flips:** `RoomSyncStore.readMessages(ids)` (`RoomSyncStore.kt:71`) is the one place
that already hands wire bytes to a peer — the natural, single hook point. The moment an id is
served there, `UPDATE messages SET unsent = NULL WHERE id IN (...)`. No `core/sync` change at
all: `SyncStore`'s interface is untouched, `Session.kt` is untouched, nothing crosses the
`core/data`/`core/sync` boundary that doesn't already cross it today.

One deliberately conservative call: flip `unsent` to `null` as soon as `readMessages()` returns
the bytes for writing, not once delivery is somehow confirmed. If a connection dies mid-flush,
this errs toward "probably sent, so no longer deletable" rather than the reverse — consistent
with the same "can never guarantee once sent" framing that justifies scoping the whole feature
this way in the first place.

## The thread case: your own insight, and why it's provably safe

Ids are content hashes. A reply can only name a real message's id as its `parent` if whoever wrote
that reply has actually seen it. So if a root you authored has `unsent = true` — meaning it has
never left this device — **nobody else has ever seen its id**, which means nobody else could
possibly have replied to it. Every message anywhere in that thread, if the root is still unsent,
must therefore be something *you* wrote locally yourself (replying to your own draft before
syncing — the normal shape of a continuation chain, README item 1, before it's ever sent).

That's not a heuristic, it's a guarantee from the id scheme itself: deleting a whole still-unsent
subtree is exactly as complete and side-effect-free as deleting its single root message would be.
This is what makes it safe to offer as a real choice rather than something the UI quietly does or
doesn't allow depending on unclear internal state.

## Repository surface

```kotlin
/** A leaf, or a root with no local-only replies under it. Fails if [id]'s author isn't you, or unsent != true. */
suspend fun deleteMessage(id: MessageId): Result<Unit>

/** A root plus every reply beneath it — only ever offered when every one of those replies is also unsent. */
suspend fun deleteThread(root: MessageId): Result<Unit>
```

Both are a straight `DELETE FROM messages WHERE id = :id` (or `WHERE thread_root = :root` for the
thread form) guarded by `author = :myAuthor AND unsent = 1` — no transaction spanning any other
table, no pruning-plan interaction, nothing left behind. `deleteThread` additionally asserts every
row under the root is itself unsent before deleting anything, as a belt-and-braces check against
the invariant above rather than trusting it blindly.

## UI

- **Entry point:** "Delete" on the per-message long-press menu in `ThreadScreen.kt`
  (`ThreadScreen.kt:340-361`), visible only when `message.author == myAuthor && message.unsent`.
  It simply isn't in the menu at all once a message has synced — no disabled state, no tooltip
  explaining why; it's absent, the same way `blockedDropped` content is absent rather than shown
  greyed-out.
- **Single message, no local replies under it:** delete immediately, no dialog — this is a
  genuinely low-stakes, fully local action with zero external effect, so a heavyweight confirm
  (the block-style "explain the consequence" pattern in `ContactActionsContent.kt`) is the wrong
  weight for it. Follow with a short-lived `Snackbar` "Undo" instead, same as
  [[feature-local-hiding]]'s hide action — cheap insurance against a stray long-press, without
  treating an unsent draft like a destructive network action.
- **A root with local-only replies under it:** the one case that needs an explicit choice —
  "Delete just this message" vs. "Delete this whole thread (N messages)" — because the two
  options genuinely diverge (orphaning the local replies vs. removing everything). A plain
  two-button dialog, no red/destructive styling needed given neither option touches anything
  outside this device.
- **Orphaned-reply rendering, if "just this message" is chosen:** falls out of existing behavior
  for free — a reply whose parent is missing already renders as "replying to a message not
  carried here" (`ThreadScreen.kt:317-321`), the same copy already used for a pruned or
  never-received parent. No new empty-state needed.

## Non-goals

- No interaction with already-synced content, ever. Not "delete your copy, note the limit" — the
  option doesn't exist past that point. If that changes later it's a distinct, separate feature
  (the original tombstone design, kept here in history rather than the doc, if it's ever revisited
  with a clearer case for the complexity).
- No bulk "delete everything I've drafted but not sent" — the per-message/per-thread entry point
  covers the real cases; a bulk action is a thin wrapper over the same primitive if ever wanted.
- No delete-request gossip message — never applicable here, since nothing eligible for this
  feature has ever been sent to gossip about in the first place.

## Open questions

- Should `deleteThread`'s "every row under the root is unsent" assertion fail loudly (surfaced to
  the user as "can't delete — part of this thread already synced") or silently fall back to
  single-message delete? Failing loudly seems right — a silent downgrade could look like the
  bigger action succeeded when it didn't.
- Worth a specific `SessionTest`/`RoomSyncStoreTest` case asserting `unsent` flips to `null`
  exactly once, on the first `readMessages()` call that includes an id, and never again — the
  column only being writable one direction is a correctness property worth pinning down in a test,
  not just in the doc.
