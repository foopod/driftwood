# driftwood

A decentralized, text-only social app built on the physics of gossip rather than the
conventions of centralized social media. No servers, no accounts, no global consistency.
Identity is a keypair; content is signed messages; the network is people syncing when they
choose to meet.

## What it is

- **Your identity is a key.** Generated on-device the first time you open the app. No
  account, no password, no email — just an Ed25519 keypair that lives on your phone. The
  public key *is* the identity; a display name is a claim, not a registration, and is always
  disambiguated by a short fingerprint derived from the key.
- **There is no server.** Nothing runs in the background and nothing phones home. Content
  only moves when two phones are physically near each other and their owners deliberately
  choose to sync — a purposeful moment, not a constant connection.
- **Messages are standalone and self-verifying.** Every message is independently signed and
  independently valid — it can be checked with nothing else in hand. A reply carries an
  optional parent link, but that link is context and structure only, never a requirement for
  validity. A missing parent costs context, never integrity.
- **Fragmentation is normal.** Devices never hold everything — storage is capped and content
  ages out on a rolling window. Partial threads are expected and rendered calmly, not as
  errors, because the software never assumes the full picture is present.
- **Fairness is structural, not moderated.** Storage is split into three tiers — the people
  you listen to, the people they talk to, and everyone else (discovery) — each with its own
  budget divided evenly across the authors currently in it. Spam resistance comes from how
  storage is allocated, not from a moderation system.

If you're new to the design, that's the whole model: a key, a signed message, a phone-to-phone
sync, and a local store that prunes itself. Everything else in this codebase exists to make
that hold up under real, messy, intermittent use.

## How it works

**Sync** happens over a direct device-to-device transport (Wi-Fi Direct, with a same-Wi-Fi-LAN
TCP fallback) — no shared network or internet required. A session is symmetric: both sides
declare who they listen to, exchange a compact hash-list of the message ids they already hold
for that scope, and each computes locally what the other is missing. Delivery runs in two
phases — a priority phase (your people's content, plus the surrounding thread context needed
to keep it whole) that's independently useful even if the connection drops, followed by a
best-effort gossip phase that trades incidental content for discovery. Sync works with
strangers as well as contacts by design — that's how a message travels further than your own
circle — with a local, private, never-shared blocklist as the only lever for refusing to carry
someone.

**Storage** is windowed (a rolling time cutoff, not an archive) and partitioned into three
tiers — listen (your subscriptions), context (strangers' contributions to your people's
threads), and gossip (everything else, for discovery) — each a hard cap, split evenly across
the distinct authors currently represented in it. Favourited messages are exempt from the
budget and never evicted. Pruning runs once per sync, after merging.

The full protocol — identity, message format, threads, tiers, storage, wire framing, and
trust/security — is the source of truth at [`site/undertow.html`](site/undertow.html).

## Status

**Built and shipping:** local keypair identity with export/backup and recovery; signed text
messages (roots and replies); the windowed, three-tier, fair-share-pruned local store; sync
over both LAN TCP and Wi-Fi Direct, with hash-list reconciliation, priority + gossip phases,
and an ambient want-list for opportunistic gap backfill; a local, never-shared blocklist; and
the core UI — feed (circle / everything else), thread view, compose, contacts, listen,
favourite, block.

**Not yet implemented** — see below.

## Project layout

```
app/                Android application (UI, sync orchestration, transports)
core/model/          Message, identity, and record types — no Android dependency
core/crypto/         Ed25519 signing/verification, mnemonic seed handling
core/store/          Shared store primitives (clock, blocklist, held-message types)
core/sync/            Wire protocol, reconciliation, session state machine — no Android dependency
core/identity/        Seed storage and Android Keystore-backed encryption
core/data/            Room-backed local persistence
site/                 driftwood.* marketing site
```

`core/model`, `core/crypto`, `core/store`, and `core/sync` are plain JVM modules, kept free of
Android imports on purpose — the protocol and data model are meant to be implementable by a
third party without depending on this app.

## Building

```
./gradlew :app:assembleDebug          # debug APK
./gradlew testDebugUnitTest           # unit tests (JVM + Robolectric)
./gradlew :app:assembleRelease        # release APK — needs keystore.properties (see .example)
```

## Planned, not yet implemented

These are deliberately deferred, not forgotten — either the design already accommodates them
without a rewrite, or they need real field-test data before it's worth picking a specific
mechanism.

1. **Long-form posts via continuation chains.** A long compose is split into a chain of
   ordinary messages — same author, same claimed timestamp, each linking to the last — so it
   propagates and is stored exactly like any other reply chain, with no new record type. On
   render, a client that understands chaining stitches the chain back into one piece; a client
   that doesn't just sees an ordinary, readable sequence of replies. A chunk that hasn't
   arrived yet renders the fragments that have, matching how any partial thread already
   behaves. A continuation character of "->" is used to mark a message which is a continuation 
   of another.

2. **Mentions.** Referencing another identity from within a message. Needs a parsing/display
   convention and a decision on whether a mention should have any effect on delivery or
   fair-share beyond ordinary thread context.

3. **Tags.** Tag-based propagation and discovery, so content can be found by topic rather than
   only by author or thread. Explicitly out of MVP scope — needs its own indexing and query
   story, and a gossip node (below) is the natural place to eventually host that index.

4. **Additional transports.** Nearby Connections, Bluetooth/BLE, Wi-Fi Aware, and a LoRa/mesh
   path, all behind the existing `Transport` interface that LAN TCP and Wi-Fi Direct already
   implement. Each one trades range, speed, and power differently; none of them require a
   protocol change to add.

5. **Local deletion of your own posts.** A modified client could let you remove your own past
   messages from your own device, with a permanent-ish local tombstone so a copy someone else
   still holds doesn't quietly reappear on a future sync. For a post that was never synced to
   anyone, this is a true delete — the only copy that ever existed is gone. For one that has
   already propagated, it removes your copy only, honestly — the same limit the blocklist
   already states plainly, since nothing can recall a message from a device it already reached.

6. **Hiding posts locally.** A pure display-layer filter for curating your own feed — never
   touches storage, never affects what you relay to others. Deliberately distinct from both
   deletion and blocking: it's a personal view preference, not a lever over what the network
   carries.

7. **Sybil-resistant fair-share.** Identity is free — a keypair costs nothing to generate — so
   equal per-author storage shares are gameable: one attacker minting hundreds of keys can
   squeeze real authors toward zero, most visibly in the gossip tier, where authors are
   strangers by definition. A graph-weighted or graph-restricted fix was considered and
   rejected — gating shares by contact/listen reachability defeats the reason the gossip tier
   exists, which is reaching people you have no path to yet. The candidate instead reuses the
   pattern the protocol already applies against backdating (`effective_time =
   min(claimed_timestamp, first_received_time)`, always computed locally): give each author's
   fair-share slot a **locally-anchored age ramp**, growing from near-zero to a full share only
   after a tunable period measured from *your own device's* first observation of them, never a
   peer-claimed one. A burst of freshly-minted sybils lands at near-zero shares immediately;
   reaching full weight costs an attacker real calendar time and — since sync is
   proximity-only — renewed physical presence, not just key generation. Paired with a
   per-session cap on newly-admitted authors, which bounds how much a single encounter can
   dilute you and forces a flood to spread across many separate meetings. Neither fully closes
   the hole against a sufficiently patient, sufficiently mobile attacker — no sybil defense in
   an open system does — but both raise the attack's cost without requiring reachability
   through anyone's graph. To be validated against real sybil behaviour in field test before
   being treated as settled.

8. **Improved (efficient) set reconciliation.** Reconciliation today exchanges a full hash-list
   of ids per session — simple, and fast enough at current scale (a worst-case full listen
   partition is 2 MiB, under 2ms over Wi-Fi Direct), but its cost is proportional to total
   holdings, not to how much two devices actually differ by. A bucketed/range-based digest
   scheme — partitioning the id space (ids are already content-hashed, so uniformly
   distributed) and exchanging one small digest per bucket, recursing only into buckets that
   disagree — would shrink bandwidth toward the size of the actual difference instead of the
   size of the whole store. That matters once storage budgets grow past what's cheap to
   exchange in full, and especially for slower future transports (BLE, LoRa) where the current
   approach wouldn't be practical. It's a real protocol change, not just an encoding tweak — it
   turns the current single-exchange reconciliation into a multi-round negotiation, needs
   version-gating so an old peer still gets the current behaviour, and needs its own
   correctness proof that it converges to exactly the same delta as the full diff, since a
   bucketing bug would mean silent message loss rather than a slower sync. Deferred until field
   test shows the naive approach is actually a bottleneck, not before.

9. **A guided tutorial for first-time users.** First-run today only covers writing and backing up
   your identity, then drops you into a guided first post (`ComposeScreen`'s `introMode`) — it
   never explains the mechanics that make this app behave differently from anything else on a
   new user's phone: that nothing moves until you deliberately sync with someone nearby, what the
   three tiers mean for what you'll actually see, or that verifying a contact's fingerprint is a
   real trust decision and not a formality. Without that, a new user's first real session is
   likely to look broken — an empty feed, a "Sync" button that requires someone else physically
   present to do anything — rather than working as designed. A short, skippable walkthrough
   covering those points, not just the compose flow, is worth doing before the app is handed to
   anyone who didn't help build it.
