# Local deletion of your own posts

Expands README item 5 ("Planned, not yet implemented"). Lets you remove one of your own past
messages from your own device. Honest about its limit from the start: for a post that never
synced to anyone, this is a true delete. For one that already propagated, it removes your copy
only — nothing can recall a message from a device it already reached, the same limit the
blocklist already states plainly (README, "How it works").

## Why a tombstone, not just a `DELETE`

Deleting the row (`MessageEntity`, `core/data/.../Entities.kt:53`) isn't enough on its own.
Reconciliation is symmetric: your next sync exchanges a hash-list of what you hold for a scope
(`core/sync/.../Session.kt:153`), and a peer who still holds your deleted message — because
they synced it before you deleted it, or because it's context in a thread they follow — will see
it missing from your hash-list and offer it back. Without something to recognize and refuse that
offer, a deleted post would quietly resurrect itself on the very next sync with anyone who kept
a copy. This is exactly the "quietly reappear" failure the README item already calls out by name.

So deletion needs two local, permanent facts, not one:

1. The message row is gone (frees storage, stops rendering it).
2. A **tombstone** — just the id and when you deleted it — persists so a re-offered copy is
   recognized and dropped, forever, without you having to notice or intervene again.

## Data model

```kotlin
/** An id you deliberately deleted locally — kept so a peer who still holds it can't hand it back. */
@Entity(tableName = "tombstones")
internal class TombstoneEntity(
    @PrimaryKey val id: MessageId,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
)
```

Scoped to `MessageId` only, not per-author — you can only ever tombstone your own messages (see
Scope below), so there's no need to key on author too.

**No TTL.** Unlike `WantEntity` (`WANT_TTL = 10` fruitless syncs) or the directory
(`DIRECTORY_TTL_MILLIS`, 180 days), a tombstone never expires. It's bounded by how many messages
you personally author and later delete — a human-scale count, not an unbounded one — so keeping
every one indefinitely is cheap and correct. Expiring a tombstone would just re-open the exact
resurrection window this feature exists to close. Same permanence rationale as the blocklist,
which also never ages out an entry on its own.

## Repository surface

Mirrors the existing `block`/`unblock` shape in `MessageRepository`
(`core/data/.../MessageRepository.kt:94`):

```kotlin
/** Deletes a message you authored: removes your copy and tombstones the id so no peer can hand it back. */
suspend fun deleteOwnMessage(id: MessageId): Result<Unit>

fun observeTombstones(): Flow<Set<MessageId>>  // for the (unlikely-needed) UI listing below
```

`deleteOwnMessage` in one transaction:

- Insert the `TombstoneEntity`.
- Delete the `MessageEntity` row.
- No pruning-plan side effect needed — this isn't a budget change, just one fewer row.

Rejected alternative: a boolean `deleted` column on `MessageEntity` instead of a separate table.
Rejected because the row needs to actually disappear (frees the tier's fair-share slot, matches
"a true delete" for the never-synced case) while the *fact of having deleted it* needs to outlive
the row — two different lifetimes, so two different places to keep them.

## Wiring into sync: the new `SyncStore` method

`core/sync` is a plain JVM module that talks to storage only through the `SyncStore` port
(`core/sync/.../SyncStore.kt:29`) — it never touches Room directly, and a tombstone is exactly
the kind of "local policy, never disclosed to a peer" the interface already has a section for
(`blocklist()`, line 66). Add a sibling:

```kotlin
/** Ids you deliberately deleted locally — checked on ingest so a peer can't hand one back. */
suspend fun tombstones(): Set<MessageId>
```

`RoomSyncStore` implements it as a straight read of the `tombstones` table.

**Ingest check.** `Session.kt`'s per-item classifier already has exactly one drop-path that isn't
a `RejectionReason` — the blocklist check (`blockedDropped`, `Session.kt:463,480`), deliberately
separate from verification failures because it's local policy, not a protocol violation. A
tombstone check belongs right next to it, same shape:

```kotlin
var tombstonedDropped = 0; private set
...
if (id in tombstones) { tombstonedDropped++; return }
```

Threaded through `PhaseOutcome`/`IngestSummary` the same way `blockedDropped` already is
(`Session.kt:41-53, 237-238, 362-363`), and surfaced on `SyncSummary` next to it — this session's
earlier `[[protocol-criticism]]`-style finding about `wants` applies here too: don't ship a silent
drop path with zero visibility. A counter costs nothing and answers "did that actually work?" the
first time someone wonders whether their deleted post really stopped bouncing back.

**Outbound side needs no change.** You never disclose a tombstone to anyone, and you never offer
a tombstoned id yourself — it's not in your `heldWithIds`/`heldBy` results once the row is gone,
so it simply doesn't come up when you're asked what you have. The only place a tombstoned id can
enter your device again is inbound, which is the one path patched above.

## Scope: only ever your own messages

`deleteOwnMessage` should refuse (return a failure `Result`, not silently no-op) for any id whose
`author != myAuthor`. This isn't a UI-only restriction — enforcing it in the repository means a
future call site can't accidentally offer it as "hide someone else's post" (that's
[[feature-local-hiding]], a completely different, non-destructive mechanism). Deleting someone
else's content is not a capability this app has or should gain; the tombstone table only ever
grows from messages you signed.

## Interaction with existing mechanisms

- **Pins.** `PinnedRootEntity` already "survives its root message being pruned" by design
  (`Entities.kt:104`, comment). Deleting a pinned root you authored falls out of that same
  behaviour for free: the thread reference survives, the row (and its text) doesn't, and the
  thread view already knows how to render a root that's absent — same as "the start of this
  thread isn't carried here" (`ThreadScreen.kt` / `HomeScreen.kt:487`), the exact copy already
  used for a pruned or never-received root. No new UI state needed there.
- **Replies to a deleted message.** Parent links are optional context, never a validity
  requirement (README: "A missing parent costs context, never integrity"). Deleting a message
  that has replies leaves those replies exactly as well-formed as they already are when a parent
  is missing for any other reason (pruned, never synced). No cascading delete of other authors'
  replies — that content isn't yours to remove, and the immutability/no-recall design explicitly
  doesn't pretend otherwise.
- **Want-list.** Nothing to do here: a tombstoned id is never something *you* want (you deleted
  it on purpose), and it stops being something you'll accept if offered, per the ingest check
  above.
- **Storage/pruning.** No interaction — one row removed, no tier accounting change.

## UI

- **Entry point:** a "Delete" item on the existing per-message long-press menu in
  `ThreadScreen.kt` (`message-context-reply`/`-profile`/`-copy`/`-pin`, `ThreadScreen.kt:340-361`)
  — visible only when `message.author == myAuthor`, i.e. exactly the same condition already used
  to route own-name taps to Settings instead of contact actions.
- **Confirmation:** reuse the in-place expand-to-confirm pattern already used for blocking
  (`ContactActionsContent.kt`'s `confirmingBlock` state), not a system `AlertDialog` — stay
  consistent with how this codebase already handles "explain the consequence, then a red
  confirm button" for another one-way local action. Copy should state the real limit plainly:
  *"Deletes your copy from this device. If this already reached other people, their copies
  aren't affected — nothing can recall a message once it's synced elsewhere."*
- **No "recently deleted" recovery list.** The whole point is the row is gone; a trash/undo
  buffer would just be a second place to store text you asked to remove, and cuts against the
  "permanent-ish local tombstone" framing in the README item itself. If undo is wanted later,
  it's a distinct, smaller feature (a few seconds of `Snackbar`-based undo before the delete
  actually commits), not a persisted archive.

## Non-goals

- No delete-request gossip message, no "please also delete this" signal sent to peers. Explicitly
  a local-only mechanism, matching the README's own framing.
- No bulk "delete everything I've posted" — out of scope for v1; the per-message entry point is
  enough to validate the mechanism, and a bulk action is a thin wrapper over the same primitive
  once it exists.
- No admin/moderation angle — this was never on the table given `deleteOwnMessage`'s author check.

## Open questions

- Should a tombstoned id also block re-*sending* it to you as thread **context** (not just
  gossip)? The ingest check above is scope-agnostic (checked once, regardless of which phase/tier
  the item arrived through), so this should already be covered — worth a specific test case
  (`SessionTest`-style) rather than trusting that by inspection alone.
- Deleting the root of a thread you started removes your only claim to having a `rootAuthor` in
  `ThreadSummaryRow` for that thread from your own device's perspective. Worth confirming the
  paginated thread-list query degrades exactly like the "root not carried here" case rather than
  dropping the thread row entirely — a thread with replies but a locally-tombstoned root should
  stay visible and openable, not vanish from the feed.
