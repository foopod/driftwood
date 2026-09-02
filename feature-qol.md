# Quality-of-life findings

A pass over the existing screens looking for small, low-risk additions that would make the app
nicer to use day-to-day, without needing a design rethink or a protocol change. Grouped by area;
each item names the file(s) involved and a rough effort size (trivial / small / medium).

## The constraint that shapes most of this list

Driftwood's whole pitch is "nothing phones home" — no servers, no accounts, content only moves
device-to-device over a deliberate sync. That rules out the obvious version of several common QoL
features:

- **Link previews / unfurling** (title, thumbnail image for a shared URL) require fetching that
  URL from the internet — which leaks your IP and read-timing to whoever hosts it, and does it the
  moment a message renders, invisibly. This is worth doing, but it must be **off by default and
  opt-in per-tap**, not automatic on render: e.g. a "Preview" affordance on a detected link that
  fetches only when tapped, exactly the same trust boundary as tapping the link to open a browser
  already crosses. Auto-fetching mime types/images for every link in a feed (the example that
  prompted this doc) is the wrong default here — it silently defeats the "nothing phones home"
  guarantee for a user who never chose to leave the device-to-device model. A tap-to-preview
  affordance, clearly labelled, is the shape that fits.
- Anything else that implies a live network call (avatar fetching, username-squatting checks
  against a registry, remote crash reporting) has the same problem and isn't included below.

Everything below is local-only: it reads data already on the device or changes how it's displayed.

## Contacts (`ui/contacts/`, `ui/contact/`)

1. **Search/filter the Contacts list.** — done `ContactsScreen.kt` renders a flat `LazyColumn` with no
   filter — fine for a handful of people, but this list only grows, and unlike Home's inline
   author search (`HomeScreen.kt:370-423`, which already does client-side name filtering) there's
   no equivalent here. Lifting that filter logic into a shared function and adding a `TextField`
   at the top of `ContactsContent` is mostly reuse, not new logic. *Small.*

2. **Show "last heard from" per contact — sync or message, whichever is newer.** — message-half
   done; the sync-timestamp half is still open (needs a schema migration, out of scope for a
   query-only batch — see below). Not just "last
   synced with them directly": since gossip can relay a contact's messages via a third party, the
   most recent *direct sync* with them isn't the same as the most recent *evidence they're still
   out there*. `MAX(latest message timestamp authored by this contact, latest direct-sync
   timestamp with them)` is the right signal — the first half is a query the message table already
   supports today (no schema change), the second half needs a small durable `last_synced_with`
   column keyed on peer author (nothing like it currently exists; `SyncLog` in
   `ui/sync/SyncViewModel.kt:31` is session-only). `ContactScreen` can then show "last heard from
   3 days ago" the way `RelativeTime` is already used elsewhere (`ThreadScreen.kt:431`,
   `HomeScreen.kt:539`) — a much better reachability signal than either half alone. *Small* for the
   message-side query alone; *medium* once the sync-timestamp column is added.

3. **Rename affordance directly from the Contacts list row** — done, not just from the dedicated
   `ContactScreen`. Currently every rename goes through `ContactRow` → navigate to `ContactScreen`
   → edit nickname → save. A long-press-to-rename on the row itself (mirroring the long-press
   pattern already used for message actions in `ThreadScreen.kt:417`) would save a hop for the
   single most common contact action. *Small.*

## Settings (`ui/settings/`)

4. **Show app version/build info.** — done. Nothing in `SettingsScreen` currently surfaces the app's
   version — no `versionName`/`versionCode` is even set in `app/build.gradle.kts` today (it
   defaults to Gradle's implicit `1`/`"1.0"`). For a peer-to-peer app where two devices may be
   running meaningfully different builds and protocol versions matter for sync compatibility,
   a "Version X.Y (build Z)" line at the bottom of Settings — sourced from `BuildConfig` — is
   both a QoL nicety and useful for anyone filing a bug report. Setting real version fields is a
   separate, larger conversation; just surfacing whatever's there is *trivial*.

5. **Storage breakdown by tier, not just budget config.** — done. `SettingsScreen.kt:144-149` shows the
   *configured* budget split ("Follow / Context / Gossip: x / y / z") but not how full each tier
   *currently* is. `StorageConfig.budgets()` is already read for the config numbers; the actual
   per-tier counts likely already exist somewhere in the pruning path (`MessageRepository.prune()`
   works from an eviction plan that must know current occupancy per tier) — surfacing "Follow:
   120/500 · Context: 340/800 · Gossip: 950/1000" would make the abstract budget concept
   concrete and let a user see at a glance which tier is about to start evicting. *Small* if the
   per-tier counts are already computed somewhere, *medium* if a new query is needed.

6. **A "View log" button next to "Send log"** — done, not instead of it. `SendLogButton`
   (`ui/sync/SyncScreen.kt:422-427`) only offers emailing `viewModel.logSnapshot()`
   (`SyncLogReport.send`, `ui/sync/SyncScreen.kt:104`) to a hardcoded dev address — there's no way
   to just look at the log text in-app. A plain dialog showing the same `logSnapshot()` string with
   a copy-to-clipboard button (reusing the `copyToClipboard` pattern already used for message text,
   `ThreadScreen.kt:475`) covers the common case — "let me see what actually went wrong" — without
   leaving the app or involving email at all. *Trivial* — the log text and the copy pattern both
   already exist; this just wires them together in a dialog.

## Sync (`ui/sync/`)

7. **Persist and show a short sync history**, not just the current session's `SyncLog`. Even a
   last-10-attempts list (peer name, timestamp, success/fail, messages exchanged) sourced from
   `SyncSummaryText`/`SyncUiState.Finished` (`SyncScreen.kt:409-413`) would answer "did that
   actually work?" after the fact, since `FinishedContent` currently only shows the result once,
   on the spot, then it's gone when you tap Done. *Medium* — needs a small durable log, but the
   summary-formatting logic (`SyncSummaryText.describe`) already exists and would just be reused
   per row.

8. **Distinguish "no peers found yet" from "still searching" more clearly.** — done `IdleContent`
    (`SyncScreen.kt:210-216`) shows the same "Looking for peers nearby…" text indefinitely — no
    indication of how long it's been searching, unlike the mid-session states which do have the
    10-second `SLOW_CONNECTION_TIMEOUT_MILLIS` stuck-detector (`SyncScreen.kt:59, 136-143`).
    Reusing that same stuck-after-N-seconds pattern for the idle discovery phase (e.g. "Still
    looking — make sure Wi-Fi/Bluetooth are on" after 15s) would apply an already-built idiom to a
    state that currently doesn't have it. *Small.*

## Thread / message view (`ui/thread/`)

9. **Long-press to copy a URL specifically**, separate from "Copy text" for the whole message.
    Now that `LinkifiedText` (`ui/common/LinkifiedText.kt`) renders detected URLs as
    `LinkAnnotation.Url` spans, a `LinkAnnotation.Url` can carry a custom
    `linkInteractionListener` — wiring a long-press-on-link to show a small "Copy link / Open
    link" menu (instead of only tap-to-open) matches what most Android text-linkification affords
    and avoids forcing "Copy text" then manually extracting the URL from the full message body.
    *Small.*

10. **A "back to top" FAB once you've scrolled off-screen.** — done Simpler than a per-card jump affordance:
    show a small FAB (à la most feed/thread UIs) once the `LazyColumn`'s (`ThreadScreen.kt:257`)
    first visible item is no longer index 0, tapping it calls `LazyListState.scrollToItem(0)`. One
    piece of state (derived from `LazyListState.firstVisibleItemIndex`) plus one `AnimatedVisibility`
    FAB, rather than something on every card. *Small.*

11. **A loading state instead of a blank scaffold.** — done `ThreadUiState.Loading -> Unit`
    (`ThreadScreen.kt:170`) and the equivalent branch in `HomeScreen.kt:247` both render nothing
    while data loads — a cold-open thread or a fresh app launch shows an empty screen for a beat
    rather than a spinner/skeleton. *Trivial* on both screens.

12. **A "Share" action alongside "Copy text".** — done `MessageCard`'s dropdown menu
    (`ThreadScreen.kt:440-470`) already has "Copy text" via `copyToClipboard`; adding a `Share` item
    that fires `Intent.ACTION_SEND` with the same text is a one-line addition next to it. *Trivial.*

13. **Drop blocked-author messages from the rendered thread entirely** — done, not just mark them.
    `ThreadUiState.Loaded.blockedAuthors` (`ThreadViewModel.kt:36`) is already tracked but never
    consulted by `renderNodes`/`MessageCard` (`ThreadScreen.kt:356-389`) — a blocked author's older
    messages (still held on-device since blocking doesn't retroactively delete, only stops future
    sync) render identically to anyone else's. Skip emitting a `MessageCard` `item(...)` for any
    node whose author is in `blockedAuthors`, but still recurse into `node.children` — the same
    "context is missing, but replies still render" pattern `detached` already covers for a parent
    that was never carried here (`ThreadScreen.kt:420-426`, `"replying to a message not carried
    here"`). That reuses an existing UI idiom instead of inventing a new one for this case. *Small.*

14. **Tap a timestamp to reveal the absolute date/time.** — done `RelativeTime.describe`
    (`ThreadScreen.kt:432`) only ever shows the relative form ("3h ago"); the raw
    `message.body.timestampMillis` is already on hand, so a tap-to-toggle absolute timestamp is a
    cheap, common convenience. *Trivial.*

15. **Auto-scroll to the first unread reply (or the bottom) on opening a thread.** — done. The `LazyColumn`
    (`ThreadScreen.kt:257`) always starts at index 0 regardless of read state, so on a long thread
    with unread replies buried deep, opening it dumps you at the root and you have to scroll to
    find what's new. *Small* — needs an unread-index lookup plus `LazyListState.scrollToItem`.

## Compose (`ui/compose/`)

16. **Confirm before discarding a non-empty draft.** — done (confirm-dialog half only; the
    `SavedStateHandle` persistence half below is still open). `ComposeContent`'s Cancel button
    (`ComposeScreen.kt:70`) calls `onCancel` unconditionally — typing a reply, then tapping Cancel
    by mistake (or the system back gesture, which isn't intercepted at all here) loses the draft
    with no confirmation, unlike the sync/add-contact screens which do gate mid-flow exits (`val
    midFlow`/`midSession` checks in `SyncScreen.kt:133` and `AddContactScreen.kt:102`). A simple
    `if (state.text.isNotBlank()) confirm else onCancel()` plus a `BackHandler` for the system
    back button would bring Compose in line with the caution already applied elsewhere. *Small*
    for the confirm dialog; going further and actually persisting the draft (keyed by
    `replyToRoot`/parent, restored via `SavedStateHandle` so it survives process death, not just
    an in-session confirm) is *medium* and worth doing later if the confirm alone isn't enough.

17. **Auto-focus the compose field and wire the keyboard's Send action.** — done `ComposeContent`
    (`ComposeScreen.kt:91-97`) has no `focusRequester`, so the keyboard doesn't appear on entering
    Compose — the user has to tap the field first even though writing is the entire point of the
    screen. Pairs naturally with adding `imeAction = Send` so the keyboard's own Send key posts,
    instead of requiring a reach down to the on-screen button. Both *trivial*.

18. **Linkify the quoted reply-target text.** — done `ReplyTarget.Message`'s quoted snippet
    (`ComposeUiState.kt:12`, rendered `ComposeScreen.kt:141-146`) is a plain `Text`, not
    `LinkifiedText` — inconsistent now that the message you're replying to may itself contain a
    link that's tappable everywhere else it's shown. *Trivial* — same helper, different call site.

19. **Give the remaining-character counter a live region.** — done The counter (`ComposeScreen.kt:104-112`)
    is a bare `Text` with no `contentDescription`/semantics, so TalkBack never announces it
    changing as you type toward the 320-char limit. *Trivial.*

## Home / feed (`ui/home/`)

20. **Search by message content, not just author name.** — done. Turned out to already be fully
    implemented and wired end-to-end (`MessageDao.pagedThreads`'s `textQuery` param does a `LIKE`
    match across every message in a thread, reachable from `HomeScreen`'s existing search box via
    `HomeViewModel`), shipped in commit `d58f042` before this doc's original survey. What that
    survey saw at `HomeScreen.kt:370-423` was only the author-name autocomplete dropdown sitting on
    top of it, which is cosmetic — the typed text was already filtering by content underneath.

21. **Truncate long previews at a word/URL boundary, not mid-token.** — done. `HomeScreen.kt:546-550` and
    `616-621` both use `maxLines` + `TextOverflow.Ellipsis`, which can cut a URL in half
    mid-string — visually fine but means a truncated preview's link (once `LinkifiedText` is
    applied there too) may not even be a valid tappable URL anymore since the regex requires a
    complete match. Worth checking `computeThreadPreview` (`HomeScreen.kt:497`) truncates on a
    word boundary before the text ever reaches `LinkifiedText`. *Trivial* if it already does this;
    *small* if it needs a boundary-aware truncation.

22. **Per-tab unread indicators.** — done. The `TabRow` (`HomeScreen.kt:279-295`) for Following/Context/Other
    shows no unread signal — parked on "Following", there's no way to tell "Context" or "Other" has
    unread threads without switching to check. A dot or count badge per tab (the "Unread only"
    toggle at `HomeScreen.kt:208-221` already implies the underlying unread state exists somewhere
    close to `pagedThreads`) would close this. *Small* — likely needs one small per-tab count query.

23. **Broaden home search to fingerprint, not just display name.** — done `HomeSearchField`
    (`HomeScreen.kt:347-423`) filters only on `name.label` (`HomeScreen.kt:378`) — a contact with no
    label set can't be found by searching their fingerprint, even though that's the one thing
    guaranteed to be unique and present. *Trivial* — broaden the filter predicate to also check
    `name.fingerprint`.

## Not investigated but worth a follow-up pass

Time-boxed this survey to the screens above (contacts/settings/sync/thread/compose/home). Two
areas that likely have their own list and weren't opened: `ui/firstrun/` (the guided intro flow —
README item 8 already flags no way to *re-trigger* it later) and `ui/blocklist/`'s empty-state
copy vs. `ContactsScreen`'s (worth a consistency pass since both are short, similar list screens).
