# Hiding posts locally

Expands README item 5 ("Planned, not yet implemented"). A pure display-layer filter for curating
your own feed. Deliberately the lightweight sibling of local deletion (shipped — see the
`unsent`-scoped delete on `MessageRepository`), and the two are meant to be read together as one
story: deletion only ever covers messages that have never been transmitted — "undo before it goes
out" — and stops being offered the instant a message syncs even once. Hiding is what covers
everything after that point: regretting a post that already reached other people, or just not
wanting to see a stranger's gossip-tier thread again. Hiding never touches storage, never affects
what you relay to others, and — unlike deletion — carries no permanence claim and no author
restriction: you can hide anyone's thread, including your own, whether or not it ever synced. It's
a personal view preference, not a lever over what the network carries or what you keep.

## Scope: thread-level, not message-level, for v1

The feed's unit is a thread (`ThreadSummaryRow`, one row per `thread_root`), not a message —
`FeedPane` (`ui/feed/FeedScreen.kt`) renders one `ThreadRow` per thread, for both the Feed and
Discover surfaces. "Hide this from my feed" naturally means *this thread stops appearing in Feed
or Discover*, which is a one-row toggle against exactly the table the
feed already groups by. Hiding an individual reply within an otherwise-visible thread is a real,
smaller feature (see Open questions) but isn't needed to deliver the README item's actual promise
— curating the feed you scroll — so v1 is thread-scoped only.

Unlike deletion, hiding is **not restricted to your own content**. The whole point is filtering
out threads *other* people posted that you don't want cluttering your feed — most usefully in
Discover (the gossip tier), which is discovery-by-strangers by design and therefore the noisiest
part of the app. You can hide any thread, including ones you started, but the common case is someone else's.

## Data model

```kotlin
/** A thread you've chosen to stop seeing in your feed — a display filter only, never touches sync. */
@Entity(tableName = "hidden_threads")
internal class HiddenThreadEntity(
    @PrimaryKey val root: MessageId,
    @ColumnInfo(name = "hidden_at") val hiddenAt: Long,
)
```

Same shape as `PinnedRootEntity` and `BlockedRootEntity` (both in `Entities.kt`)
— keyed on the thread root, survives that root being pruned or never held at all (you can hide a
thread whose root you never received, same as pinning one).

## Repository surface

```kotlin
suspend fun hideThread(root: MessageId): Result<Unit>
suspend fun unhideThread(root: MessageId): Result<Unit>
fun observeHiddenThreads(): Flow<Set<MessageId>>  // for the review screen below
```

Both are a single-row insert/delete against `hidden_threads` — no pruning-plan interaction, no
transaction spanning other tables. Simpler than `block`/`unblock`
(`MessageRepository.block`/`unblock`), which also touches the blocklist and re-runs pruning; hiding
changes nothing about what's held, so there's nothing else to do.

## Where filtering happens: the query, not the view

This has to be a SQL-level filter on `MessageDao.pagedThreads` (`DriftwoodDatabase.kt`), not a
post-hoc `.filter {}` in `FeedViewModel` or `FeedPane`. `pagedThreads` returns a
`PagingSource<Int, ThreadSummaryRow>` — filtering after paging has already sliced pages breaks
page boundaries and per-page counts (a page could come back empty, or shorter than requested,
for reasons unrelated to actually reaching the end of the list). It has to be excluded before
paging, same as every other filter the query already applies (`tabs`, `authorFilter`, `textQuery`, all
folded into the same `WHERE`). `pagedThreads` already takes a `tabs` list — Feed passes
FOLLOWING+CONTEXT, Discover passes OTHER — so one hidden-thread clause covers both surfaces.
`pagedActivity` should *not* be filtered: a reply to something you wrote is addressed to you
whether or not you hid the thread.

Add one more clause to the existing `WHERE`:

```sql
AND m.thread_root NOT IN (SELECT root FROM hidden_threads)
```

and one bind param, `excludeHidden: Boolean` — default `true` for the Feed and Discover panes,
`false` for the "Hidden threads" review screen (below), which needs the opposite query. Simplest
correct approach: reuse `pagedThreads` with the polarity flipped
(`AND (:excludeHidden = 0 OR m.thread_root NOT IN (...)) AND (:excludeHidden = 1 OR m.thread_root IN (...))`)
rather than write and maintain a second near-duplicate query.

## What hiding does *not* touch

- **Sync.** No `SyncStore` change at all. A hidden thread keeps syncing, keeps holding its slot
  in whichever tier it's in, and keeps propagating to others exactly as before — hiding is
  invisible to `core/sync` by design, which is the whole distinction from deletion.
- **Pruning/storage budgets.** Untouched. A hidden thread is pruned on exactly the same schedule
  and fair-share rules as a visible one.
- **Unread state.** A hidden thread keeps accumulating unread messages underneath the filter.
  Whether that should count toward "you're all caught up" is an open question below. The Feed itself no longer shows unread state; it only matters for
  the Activity badge — the honest default is that it
  shouldn't silently inflate an unread badge for something you deliberately chose not to see.
- **Direct navigation.** Opening a hidden thread by its id still works exactly like any other
  thread — from a search result, a notification if one's ever added, or a still-visible reply to
  it in a *different* unhidden thread's preview. Hiding removes a feed row, not the content or
  the ability to reach it directly. `ThreadScreen` needs zero changes.

## UI

- **Entry point:** long-press on a `ThreadRow` in `ui/feed/FeedScreen.kt`, which currently has
  only a single `clickable` on its outer `Column` to open the thread and no long-press menu at
  all (the inner author/preview clickables must keep working). Add the same
  `combinedClickable(onClick = ..., onLongClick = ...)` + `DropdownMenu` pattern already used
  per-message in `ThreadScreen.kt`, with one item,
  "Hide from feed" — no confirmation step needed, since unlike block/delete this is instantly and
  trivially reversible.
- **Undo, immediate:** a `Snackbar` with an "Undo" action right after hiding, since the row just
  disappeared out from under a list the user was scrolling — the same "did that actually work,
  and can I take it back" instinct the copy-message-text/pull-to-sync additions were built to
  satisfy directly, rather than trusting a silent state change.
- **Review screen:** a "Hidden threads" screen under Settings, structurally identical to
  `ui/blocklist/BlocklistScreen.kt` (list of rows, each with an unhide action) — reuse that screen's shape
  rather than invent a new one, since the two are functionally the same "list of ids you can
  remove from a set" pattern. Each row needs *some* preview (root author name via `AuthorName`,
  same as `BlocklistScreen`'s contact rows) even for a hidden thread whose root you no longer
  hold — falls back to "a hidden thread" / fingerprint-only, same graceful-degradation precedent
  as pinned/blocked roots elsewhere in the app.

## Non-goals

- No author-level or keyword-level hiding in v1 — thread-scoped only, per Scope above. Both are
  natural extensions of the same `hidden_threads`-shaped mechanism later, not a redesign.
- No interaction with the blocklist. Blocking is a network-carry decision with real teeth
  (removes content immediately, stops listening, never discloses who); hiding is strictly softer
  and shouldn't be conflated with it in the UI or the data model, even though both end up
  filtering the feed.

## Open questions

- Should a hidden thread's unread count still surface anywhere (a small "3 hidden" affordance
  near the Activity nav item), or stay fully invisible until unhidden? Fully invisible is
  simpler and matches "personal view preference" most literally; a count risks turning hiding
  into a second inbox people feel pressure to clear.
- Does hiding the *root* of a thread someone replies to later re-surface it implicitly anywhere
  (e.g. a reply from someone you follow appearing in a "known reply" preview elsewhere)? Worth a
  specific test once `pagedThreads`' hidden-thread clause is in, since the query's several
  `LEFT JOIN`s for reply previews are independent of the top-level `WHERE` filter and could in
  principle surface hidden-thread content in an unexpected place if not checked.
