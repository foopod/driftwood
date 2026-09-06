# What's next

Running list of what's queued, most immediate first. Delete items as they land.

Last updated 2026-09-06, after the Feed / Activity revamp (`0.1.1`, commits `1e4114b`, `be372a9`).

## Finish shipping 0.1.1

- [ ] `git push` the two local commits.
- [ ] Tag + publish the `0.1.1` GitHub release with the APK
      (`app/build/outputs/apk/release/driftwood-0.1.1.apk`,
      sha256 `25a8f298d61cb508497e54578e58e4d27838d67fe697491b5dfdc6a2c8287bd3`).
      `site/index.html` already links `releases/tag/0.1.1`. Decide at tag time whether a revamp
      this size should be `0.2.0` rather than a patch.
- [ ] **Run the new androidTests on the Samsung** (they only compile in the dev env — no emulator):
      `core/data` → `ActivityQueryTest` and `DriftwoodMigrationTest.migration5To6AddsIndexesWithoutLosingRows`.
      This is the only real verification gap — the Activity SQL and the v6 migration have never
      actually executed.
- [ ] Dogfood the new nav on both devices: is Discover-as-third-nav-item right, does Activity feel
      useful, does the badge behave, does scroll position survive tab switches on-device.

## Next feature — pick one

- **Local hiding** (`feature-local-hiding.md`) — recommended. Last fully-designed, low-risk item;
  an actual promised feature (README "Planned" #5); drops cleanly into the new shell (long-press a
  Feed row → "Hide from feed", plus a "Hidden threads" screen under Settings mirroring
  `BlocklistScreen`). Design doc is complete; no open questions blocking.
- **Guided tutorial** (README "Planned" #8) — jumps ahead of local hiding *if* the app is about to
  go to people who didn't help build it. Doc says a new user's first session "is likely to look
  broken" without it.

## Batch into the next DB migration (v7)

Each needs a schema change — do them in one migration:

- **QoL #2** (`feature-qol.md`): "last heard from" per contact — the *sync-timestamp* half. Needs a
  durable `last_synced_with` column keyed on peer author. Message-side half already shipped.
- **QoL #7**: persist a short sync history (last ~10 attempts: peer, time, success/fail, count).
  Needs a small durable log table; `SyncSummaryText.describe` is reused per row.
- **QoL #16**: compose draft surviving process death — persist keyed by `replyToRoot`/parent via
  `SavedStateHandle`. The confirm-on-discard half already shipped.

## Smaller / opportunistic

- **Post-sync local notification** — deferred during the revamp, worth reconsidering now that
  Activity is a real surface. One local `notify()` at sync completion ("Sync with Alice — 3 replies
  to your posts"), tapping through to Activity. It's the natural "come back and catch up later"
  trigger. Fired synchronously at sync end, so not a background process. Needs `POST_NOTIFICATIONS`
  (minSdk 33 → always) + a channel. Hook point: `SyncCoordinator.runSession` right after
  `SyncUiState.Finished`. Get the count from a post-sync query (activity replies with
  `first_received_time >= sessionStartMillis`), not by plumbing data out of `:core:sync`.
- **"Mark all read" in Activity** — `MessageDao.markActivityRead(myAuthor)`:
  `UPDATE messages SET read = 1 WHERE parent IN (SELECT id FROM messages WHERE author = :myAuthor)`.
- **QoL follow-up pass** on `ui/firstrun/` and `ui/blocklist/` empty-state copy (consistency with
  the other list screens).
- **`ui/feed` cleanup**: `FeedScreen.kt` contains `FeedPane` — rename the file, or split
  `FeedPane` + the `ThreadList`/`ThreadRow`/`FeedSearchField` helpers out.

## Bigger bets (need field data or their own design pass)

Long-form posts via continuation chains; mentions; tags; additional transports (BLE, Wi-Fi Aware,
LoRa); sybil-resistant fair-share; efficient set reconciliation. All in README "Planned, not yet
implemented".

## Reference

- Revamp design + implementation notes: `~/.claude/plans/logical-zooming-nebula.md`
- QoL backlog: `feature-qol.md`
- Local hiding design: `feature-local-hiding.md`
