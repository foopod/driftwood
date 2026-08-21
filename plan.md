# Gossip-Native Social Network — Android MVP Plan

A decentralized, text-only social app built on the physics of gossip rather than the
conventions of centralized social media. No servers, no accounts, no global consistency.
Identity is a keypair; content is signed messages; the network is people syncing when
they choose to meet.

---

## 1. Guiding principles

These are the design commitments the whole build derives from. When a decision is
unclear, resolve it back to these.

- **Design from the medium, not onto it.** Gossip is intermittent, transitive, and
  partial. Do not import guarantees (global consistency, addressed delivery, complete
  threads, permanent deletion) the medium can't keep.
- **Messages are standalone and self-verifying.** Every message is independently signed and
  independently *valid* — it can be checked with nothing else in hand. Parent links are
  *context and structure only* — never validity or trust. A missing parent costs context,
  never integrity. (Whether a message still makes *sense* without its parent is a matter for
  its author; the system's job is that it still verifies and still renders.)
- **Fragmentation is normal.** Partial threads are expected and rendered calmly, not as
  errors. Every message stays *readable* without its neighbours, because the software never
  assumes context is present — that is a constraint on the system, not an instruction to
  authors. How much context to include is the writer's call, and they will calibrate it
  from how impermanent the medium turns out to feel. The UI does not advise them.
- **Purposeful sync.** Two present devices sync because the users choose to. No
  background execution in the MVP.
- **Bounded and windowed.** Devices never hold everything. Storage is capped; content
  ages out. New devices onboard fast because there's little to pull.
- **Structural safety over policy.** Spam resistance and fairness come from how storage
  is allocated (per-identity fair-share), not from moderation systems.

---

## 2. MVP scope

### In scope

- Local keypair identity generation, with export/backup and import (multi-device).
- Signed text messages: roots and replies (root-id backbone + optional parent-id).
- Local message store (windowed, per-identity partitioned, pruned).
- **Purposeful sync between two present devices over Wi-Fi Direct** (Wi-Fi P2P — direct
  device-to-device, no shared network or hotspot required; this is the "meet in person and
  sync" transport the whole design is about). **Syncing with a non-contact (a stranger you
  just met) is explicitly supported** — see §5.
- Reconciliation (hash-list diff) → delta transfer → merge, in two sync phases
  (priority: listen + context content; then best-effort gossip discovery content).
- Ambient want-list for opportunistic gap backfill during sync (no user action).
- **Local blocklist** — blocked authors' content is dropped at pruning, and their threads
  with them (§4). Never delivered onward.
- Feed UI (two tabs: *circle* / *everything else*), thread view (tree-where-held,
  flattened-to-root where not), compose, listen, favourite, block.

### Explicitly out of scope (MVP)

- Background / ambient sync (deferred; design so it can be added).
- DMs / encrypted messages (architecture accommodates; not built).
- Private / opportunistic listening (MVP listen is **public** — declared to every peer you
  sync with, strangers included; private listening needs a different, interest-hiding sync
  mechanism, deferred).
- Tags / tag-based propagation.
- Any *shared* moderation — blocking is local-only, private, and never propagated. No
  reporting, no shared blocklists, no reputation.
- Nearby Connections / Bluetooth / BLE / Wi-Fi Aware / LoRa transports. (MVP ships two
  transports behind one interface: same-Wi-Fi-LAN TCP is built **first** as the protocol
  test rig and permanent fallback, then Wi-Fi Direct as the primary — see §7/§8. Other
  radios come later.)
- Async `ask` routed through the network (synchronous/opportunistic only).
- Play Store release (sideload / internal testing for MVP).

---

## 3. Data model

### 3.1 Identity
- Ed25519 keypair generated on-device.
- **Public key = identity.** There is no handle registry and no global namespace. A name is
  never what identifies someone — only what they are called.
- Private key stored in Android Keystore where possible; **exportable** as an encrypted
  backup (recovery phrase or key file). Forced backup step on first run.

**Names: two kinds, never blurred.**

| | Petname | Claimed nickname |
|---|---|---|
| Set by | **you** | its owner |
| Travels | never | yes, signed by its owner |
| Trust | trustworthy — you bound that name to that key yourself | a claim, nothing more |
| Lives in | `contacts` | `directory` (§3.5) |

- A **petname** is what you choose to call someone after confirming their key in person
  (§6, QR exchange). It is local, and it is the only kind of name that can be trusted.
- A **claimed nickname** is self-asserted, signed by the key it belongs to, and propagates
  through sync so that strangers in the gossip tier are readable rather than hex.

- **Collision is the default outcome, not an edge case.** Nothing stops two keys claiming
  the same nickname, and in an open network you should expect exactly that, including
  deliberately. This is Zooko's triangle — human-meaningful, decentralised, secure: pick
  two. The design keeps *decentralised* and *secure*, so names cannot be identifiers.
- Because the nickname is signed by its own key, a relaying peer cannot rewrite it; the
  worst a peer can do is withhold it.
- **Display rule (§6): a petname always wins.** A petname is plain text. A claimed nickname
  sits on a **colour derived from the key**, with a short fingerprint beside it. The two must
  never look alike: "I vouched for this person" and "this person says so" are different
  claims, and the difference is structural rather than a matter of shading.
- **What the colour is for, and what it is not.** It catches the common, honest case — two
  people genuinely both called Sam — at a glance, without making every line carry hex. It is
  not a defence: there are only a handful of reliably distinguishable hues, so matching a
  target's colour costs a few dozen throwaway keypairs. The hue is drawn from key bytes the
  fingerprint does not display, so at least the two channels are independent and an
  impersonator has to match both.
- **Never colour alone.** Around 8% of men have some colour-vision deficiency; if colour were
  the only differentiator they would have none. The fingerprint is the non-colour channel and
  stays visible.

### 3.2 Message (the primary data type)

| Field | Notes |
|---|---|
| `v` | Format version. `1` for MVP. First field so a decoder can branch before parsing the rest. |
| `id` | Content hash (see canonical form + construction order below). Content-addressed. Not part of the hash preimage or signature. |
| `author` | Public key of the author (32 bytes, Ed25519). |
| `root` | Id of the thread root. **Empty on a root message** — a root is *defined* as a message with an empty `root` field, and its own `id` then serves as the thread's root id everywhere else. Always present (possibly empty) on replies. See construction order below. |
| `parent` | Id of the specific message being replied to. **Optional** (enrichment). Empty/absent on roots and on replies where the author didn't hold/target a specific parent. |
| `timestamp` | Author-claimed time, Unix milliseconds UTC. **Untrusted.** See `effective_time` in §4 for ordering/windowing. |
| `text` | UTF-8 text, **max 320 characters** — Unicode scalar values (code points), not bytes and not UTF-16 units. **NFC-normalize first, then count** (see below). Enforced on create and on ingest; over-length is rejected. |
| `sig` | Ed25519 signature over the canonical preimage (see below). Not part of the hash preimage. |

Messages carry no name. Who wrote something is the `author` key; what they are *called* is a
separate, much smaller signed record (§3.5), so a name is stored once per identity rather
than repeated on every message that identity ever writes.

**Canonical serialization (the foundation — two impls must agree byte-for-byte).**
- Fields are serialized in this fixed order: `v`, `author`, `root`, `parent`, `timestamp`, `text`.
  (`id` and `sig` are **excluded** from the preimage.)
- Encoding: length-prefixed binary. Each field is emitted as a `uint16` big-endian byte
  length followed by its bytes. Integers (`v`, `timestamp`) are fixed-width big-endian
  (`v` = 1 byte, `timestamp` = 8 bytes) and still length-prefixed for uniformity. Absent
  `parent` is encoded as length `0`.
- `text` is serialized as UTF-8 with **Unicode NFC normalization applied before hashing/
  signing** (so visually identical text can't produce two ids). Normalize once, at create
  time, and store the normalized form.
- **Normalize before counting — the order is load-bearing.** NFC changes code-point count:
  `e` + combining acute is two code points, the composed `é` is one. If the author counts
  before normalizing and a receiving peer counts after, the two disagree about whether
  identical bytes are valid. The sequence is fixed: **normalize → count → serialize**, and
  ingest re-counts the normalized form it received.
- **Unpaired surrogates are rejected** at create and at ingest. A UTF-16 string can hold one,
  and encoding it to UTF-8 silently substitutes `?` (U+003F) — which would make
  encode→decode→encode non-idempotent and let two peers derive different ids from "the same"
  text. Text that is not well-formed Unicode never enters the store.
- No JSON in the preimage — JSON field ordering/whitespace is not guaranteed stable and
  must never be what gets hashed. (JSON/CBOR may be used on the wire *around* messages, but
  the hash/signature are always over this canonical binary preimage.)

**Construction order (resolves the id/root/sig circularity — single pass, no fixpoint):**
1. Assemble fields `v`/`author`/`root`/`parent`/`timestamp`/`text`. For a **root**, `root`
   is **empty** (length `0`). For a **reply**, `root` is the target thread's root id.
2. Compute `id = hash(canonical_preimage)` — one pass, always. There is no two-pass fill and
   no self-referential `root`: a root message carries an empty `root` on the wire and in
   storage, and *its own `id` is the thread's root id* that replies target.
   - Rationale: `root = id` cannot be made true by hashing (changing `root` changes `id`),
     so the field would always be a lie. Empty-means-root keeps every field honest and the
     preimage single-pass.
   - Convenience: the store may materialise a derived, non-signed column
     `thread_root = (root if non-empty else id)` for indexing. It is **never** serialized,
     hashed, or transmitted.
3. Sign the canonical preimage → `sig`.
4. Store `id` and `sig` alongside the message but never inside the preimage.

- **Hash:** SHA-256 over the canonical preimage. `id` is the raw 32 bytes (hex-encoded
  only for display/QR).

**Wire form (one message on the wire).** The preimage deliberately excludes `id` and `sig`,
so it is not by itself transmittable. A single message on the wire is:

```
id (32 bytes) || sig (64 bytes) || canonical preimage (variable)
```

Fixed-width prefixes, no length header, trivially separable. The important property: a
receiver **hashes the preimage bytes exactly as received** rather than decoding and
re-serializing them. Re-serializing would silently repair a hostile or buggy encoding —
normalizing away the very tampering the hash exists to catch — and could make a message
verify that should not. Decode to *inspect* the fields; hash and verify the *received bytes*.

- **Verify on ingest — cheap checks before expensive ones**, so a peer can't make us burn CPU
  on signature verification for obvious garbage. In order:
  1. **Decode strictly** (below).
  2. **Structural checks:** `text` decodes as strict UTF-8 (no replacement characters);
     text is well-formed Unicode (no unpaired surrogates); **text is already NFC** — a
     peer's non-normalised text is rejected, not repaired; `text` length within
     `MSG_MAX_CHARS`; `v` recognized; `root`/`parent` are 32-byte ids or empty; `author` is
     32 bytes; `timestamp` is non-negative.
     - *Why reject rather than accept non-NFC:* the whole point of normalising is that
       visually identical text cannot produce two different ids. If ingest accepted
       non-normalised text, the same visible message would have two valid ids and two
       valid signatures — exactly the duplication NFC exists to prevent. Repairing it is
       worse still: that would change the bytes the id was computed over.
  3. `sha256(received preimage) == id`.
  4. `sig` verifies against `author` over the received preimage.
- **Strict decoding — reject, never repair.** Any of these is a rejection: a declared length
  that overruns the buffer; trailing bytes after `text`; `v` field length ≠ 1; `timestamp`
  field length ≠ 8; `author` length ≠ 32; `root` or `parent` length ∉ {0, 32}; unknown `v`.
  A decoder is an attack surface — permissiveness here is what turns a format bug into a
  security bug.
- **Any failure → reject: the message is not stored, not counted, and not forwarded** (§5).

- **Content-addressing** gives dedup, integrity, and a single identifier that serves as
  both `ask`-target and diff-unit.
- **Ordering:** sort by (`effective_time` ascending, then `id` bytewise-ascending as the
  deterministic tiebreak — a plain unsigned lexicographic comparison of the 32 raw id
  bytes, cheap and total) so all clients converge on the same order even when timestamps
  collide or lie.
- **Threading:** a thread is "all messages whose `thread_root` is R" — i.e. the root
  message with `id == R` (if held) plus every reply carrying `root == R`. Render as a tree
  wherever `parent` is held; attach to root (flattened, with a quiet marker) wherever
  `parent` is missing. **You may reply to a thread using only its `root` id** — you need
  hold neither the root message nor any other message in it. Replying to a thread you've
  only seen a fragment of is an intended, encouraged mechanic, not an error.
- **Root ids outlive root content.** A root id is a permanent, cheap identifier; the root
  *message* is prunable like anything else. Long-running threads whose original root nobody
  still holds are an expected, normal end-state — the thread persists as an id that replies
  keep hanging off. Roots are therefore **not** want-list targets (§3.3); only parents are.

### 3.3 Local state (not signed, per-device)
- **Listen list** — identities whose messages you prioritize. **Public in MVP** (it is
  sent to the peer during sync so they can filter what to deliver — see §5. Private /
  opportunistic listening is deferred because it requires a different sync mechanism that
  doesn't reveal your interests, not just a UI toggle).
- **Favourites** — pinned messages/threads, exempt from pruning.
- **Blocklist** — identities whose content you refuse. **Local and private** — never
  declared to a peer, never part of the hash-list diff (that would leak who you've blocked
  and complicate reconciliation). Enforced at two points only: content from a blocked
  author is **excluded from delivery** to peers, and **dropped at pruning** (§4).
- **Blocked roots** — root-ids whose root message was authored by a blocked identity,
  remembered separately so that replies into that thread can keep being dropped after the
  root message itself is gone. (Blocking a thread is only possible for threads whose root
  you have actually seen — see §4.)
- **Want-list** — orphan parent-ids you'd accept if a peer has them; each entry has a TTL.
  Parents only; roots are never wanted (§3.2).
- **Directory** — every identity whose claimed nickname you have heard, from anywhere:
  `author`, `nickname`, `claimed_at`, `first_received`, `last_seen_post`. Purely a cache of
  other people's claims — losing it costs readability, never integrity.
- **Storage config** — an overall storage budget plus the three partition caps
  (`listen` / `context` / `gossip`), derived from the default split unless a power user
  overrides them.
- **Per-message local fields** — `first_received_time`, `favourite`, `read`. Local only;
  never serialized into the message or transmitted. (`read` drives the unread highlighting
  in §6; messages arrive out of order, so "new to me" is per-device state, not a function
  of time.)

### 3.4 Tunable constants (starting values — chosen to be reasonable, expected to change in testing)

Keep every one of these behind a single named constant, not inlined, so tuning later is a
one-line change. The values are deliberate starting points, not researched optima.

| Constant | Starting value | Notes |
|---|---|---|
| `MSG_MAX_CHARS` | 320 | Unicode scalar values, enforced on create and ingest. |
| `WINDOW_DEFAULT` | 90 days | User-configurable; applied via `effective_time`. |
| `PARTITION_SPLIT` | listen 0.50 / context 0.20 / gossip 0.30 | Hard-coded MVP default, power-user overridable; single source constant; adaptive later. |
| `NOMINAL_MSG_SIZE` | 512 bytes | Only used to convert a storage cap into a message-count budget. |
| `WANT_TTL` | dropped after 10 unsatisfied syncs | Prevents chasing permanently-gone parents. |
| `HASH` | SHA-256 | Over the canonical preimage; id = raw 32 bytes. |
| `SIG` | Ed25519 | 32-byte pubkeys, 64-byte signatures. |
| `PROTOCOL_VERSION` | 1 | Exchanged in the handshake; mismatch refuses. |
| `MSG_FORMAT_VERSION` (`v`) | 1 | First field of every message. |
| `SESSION_INTAKE_CAP` | 1000 | Max messages accepted per phase per session. Starting point; revisit in M2 against observed sizes. |
| `VERIFY_FAIL_CUTOFF` | 20 | Rejected (unverifiable) messages from one peer in one session before the session is aborted. |
| `NICKNAME_MAX_CHARS` | 32 | Unicode scalar values after NFC, like `MSG_MAX_CHARS`. |
| `DIRECTORY_TTL` | 180 days | Since `last_seen_post`, before a name ages out. Deliberately longer than `WINDOW_DEFAULT` so a partly-pruned thread still shows who said what. |
| `MIN_SDK` | 33 (Android 13) | Set by Wi-Fi Direct + `NEARBY_WIFI_DEVICES`/`neverForLocation`; API ≤ 32 would force a location permission. See §7. |

### 3.5 Profile record (the second data type)

A tiny signed claim: *this key calls itself this*. Deliberately separate from messages —
names change, messages do not, and one row per identity beats the same name repeated across
every message that identity ever wrote.

| Field | Notes |
|---|---|
| `v` | Format version. `1`. |
| `author` | The key making the claim (32 bytes). Also the primary key — a profile is not content-addressed, because it is mutable by design. |
| `nickname` | UTF-8, **max 32 characters** (Unicode scalar values, NFC-normalised, counted after normalising, exactly as `text` in §3.2). |
| `timestamp` | Author-claimed time. Untrusted; see below. |
| `sig` | Ed25519 over the canonical preimage. |

**Canonical form** follows §3.2 exactly — same length-prefixed binary encoding, same field
discipline, same strict decoding — so there is one serialization idea in the system rather
than two. Field order: `v`, `author`, `nickname`, `timestamp`. There is no `id`: profiles are
keyed by `author`.

**Nickname validation (on create and on ingest).** NFC-normalise, then count code points
against the cap. Reject: unpaired surrogates; any Unicode **control, format or bidi-override**
character; leading or trailing whitespace; an empty result.

The bidi rule is not fussiness — `U+202E` (right-to-left override) reverses how following
text renders, so `evil\u202Eonoj` can display as something else entirely. It is the same
trick as the "Trojan Source" attacks, it costs one check to close, and a name is precisely
where someone would try it.

**Latest claim wins**, ordered by `effective_time` (§4) rather than raw claimed time — so
forward-dating cannot pin a name permanently, for the same reason it cannot pin a message to
the top of a feed.

**How profiles sync.** They do not get their own reconciliation, want-list or phase.
Profiles **ride along with delivery**: when a peer streams a delta covering messages from N
authors, it attaches the profile records it holds for those N authors. Two consequences:

- Gap-filling is automatic. Receiving somebody's content is exactly when you need their name,
  and it arrives together.
- There is **no independent flooding vector**. Profiles cannot be pushed for keys whose
  content you were not already receiving, so the directory is bounded by the message caps
  rather than needing caps of its own.

Verification on ingest mirrors §3.2: strict decode, structural checks, then signature against
`author`. A profile that fails is rejected outright, never stored.


---

## 4. Storage & pruning

**Effective time (used for ordering *and* windowing; defends against backdating).**
- `effective_time = min(claimed_timestamp, first_received_time)`, where
  `first_received_time` is the local wall-clock time this device first ingested the
  message (stored locally, never transmitted, never part of the message).
- **What this defends against: forward-dating.** A message can't legitimately reach you
  before it existed, so a peer presenting a message stamped in the future (or later than it
  really is) gains nothing — the earlier *received* time wins, and the message can't buy
  itself extra relevance, feed position, or extra time inside your window. `min()` caps
  every claimed time at "no later than when it actually arrived here."
- **What it deliberately does not do: rescue backdated or slow-travelling content.** A
  message claiming to be older than your window keeps that old time and is simply
  out-of-window — not carried. The same is true of a message that genuinely took longer
  than the window to reach you. Backdating is therefore self-defeating rather than
  dangerous: the only thing a liar buys is their own content ageing out sooner.
- **Consequence, accepted deliberately:** a message's reach is bounded by the window
  measured from when it was *authored*, not from when it arrived at each hop. Content that
  takes 90 days to travel three hops cannot travel a fourth. This is intended — the network
  carries a moving present, not an archive.
- A message you *authored* has `first_received_time` = its creation time.

**Windowing.**
- The window is **user-configurable, default 90 days** (`WINDOW_DEFAULT`, §3.4). A message
  is *in-window* if `effective_time >= (now - window)`.
- Out-of-window messages are not ingested and are pruned once they age out. A message
  legitimately older than the window simply isn't carried; that's expected fragmentation.

**Partitions (three, each a hard cap).**
- Three partitions: `listen`, `context`, `gossip`, each with its own hard storage cap.
- **Default split (MVP):** a sensible default the user never has to touch, with
  **power-user override** available in settings. Starting default: **listen 50% /
  context 20% / gossip 30%** of the total storage budget. (Rationale: listen is the core
  value and gets the most; context needs enough to keep your people's threads coherent but
  is secondary; gossip keeps a meaningful discovery window without dominating.) These are
  starting numbers, expected to move with testing — see `PARTITION_SPLIT` in §3.4.
- The user sets an overall storage budget (or accepts a default); the split divides it.
  Power users can adjust the three-way ratio; everyone else gets the defaults.
- *(Post-MVP: make the split **adaptive** rather than fixed — e.g. gossip-favoured when the
  listen list is sparse (a new user needs discovery and has little to keep), tilting toward
  listen/context as the listen list fills, with a permanent **gossip floor** so discovery
  never drops to zero and a user can't calcify into a bubble. The fixed default over-serves
  discovery for established users and under-serves it for brand-new ones; the adaptive
  version fixes both. Keep the split behind one source so this is a contained change.)*

**Which partition a message belongs to (classification — THREE tiers).**
Every held message is classified into exactly one of three tiers, each with its own
storage partition and cap:

- **Listen tier** — `author` is in your listen list. Your subscriptions. Highest priority.
- **Context tier** — `author` is *not* listened to, but the message shares a `root` with
  at least one message you hold whose author you *do* listen to (the **thread-bump** rule).
  These are strangers' contributions to your people's threads: kept so conversations stay
  whole, but understood as *context, not subscriptions*. Middle priority.
- **Gossip tier** — everything else: incidental content carried for discovery. Lowest
  priority.

- A message's tier can *change* at sync time when you gain/lose a listened author, or when
  a thread gains/loses a listened participant (e.g. a gossip-tier message becomes context
  once you hold a listened author's message in the same thread; context becomes listen if
  you start listening to its author). Reclassification is evaluated at sync time, not
  continuously.
- Precedence when a message could qualify for more than one: **listen > context > gossip.**

**Fair-share (the unit is MESSAGE COUNT — text is small and uniform enough).**
The three tiers now behave *uniformly*, which is simpler than the earlier exempt-context
scheme: each tier is its own partition with its own budget, and within each the rule is
identical.

- Each partition has a total message-count budget (its storage cap ÷ `NOMINAL_MSG_SIZE`).
- Within a partition, the budget is **divided equally across the distinct authors currently
  represented in that partition**: each author gets `partition_budget / author_count`
  messages (its **share**).
- If some authors hold fewer than their share, the unused remainder is redistributed
  equally among authors over their share. One redistribution pass for MVP.
- **Eviction:** within each over-quota author, drop **oldest-first by `effective_time`**
  until at or under its share.
- **Favourited messages are exempt and invisible to this math**: not counted toward any
  share or partition budget, never evicted. Caps are hard **for non-favourited content
  only**.
- **Identity is free, so equal shares are gameable.** An author flooding under 500 fresh
  keypairs takes 500 shares and squeezes real authors toward zero — most visibly in the
  gossip partition, where authors are strangers by definition. **MVP accepts this
  deliberately**: restricting shares to authors reachable through your contact/listen graph
  would harden the store at exactly the cost of the thing gossip exists for — letting
  unknown people reach you. Early on, openness matters more than the attack. The blocklist
  is the MVP's manual answer; graph-weighted or pooled shares for unknown authors are the
  post-MVP fix (§10), and the split-behind-one-constant rule keeps that contained. Watch it
  in field test (M5).

Because context is now its own bounded partition (not an exempt free-rider in the listen
tier), **total storage is predictable again**: it's the sum of the three caps (plus
whatever favourites the user has pinned). This resolves the earlier "can't anticipate
context storage" problem — context can't grow without bound; it's capped like everything
else, and when full, its own oldest content is evicted first.

**Blocked content (drop-at-prune).**
- Blocking is enforced in the pruning pass, not in reconciliation. Keeping the blocklist out
  of the hash-list diff means a peer never learns who you've blocked, and the diff stays a
  pure set operation.
- The prune pass drops, unconditionally and ahead of all fair-share math: (a) every message
  whose `author` is blocked; (b) every message in a **blocked root** — a thread whose root
  message was authored by a blocked identity. Root authorship is recorded into the
  `blocked_roots` set at the moment of blocking (and whenever a root from a blocked author is
  ingested), so the thread stays blocked after the root message itself is pruned.
  - Honest limit: a thread can only be blocked *by root* if you have actually held its root
    and know its author. Replies into a blocked-author thread whose root you never saw are
    indistinguishable from any other reply, and will be carried. Blocking the author still
    removes their own messages everywhere.
- Blocked content is also **excluded from delivery** (§5) — a cheap local filter over the
  outgoing delta, so you never relay for someone you've blocked in the window between the
  block and the next prune.
- Favourite does **not** protect blocked content; blocking wins.

**Directory pruning (names, not messages).**
The directory is a cache, so it is pruned on its own terms rather than by fair share. A row
is kept while **any** of these hold:

- the author is in your **listen list** — immune, since a name you deliberately follow is
  the last one you would want to lose;
- the author is a **contact** (you have a petname for them anyway);
- you still hold at least one message from them.

Otherwise it ages out `DIRECTORY_TTL` after `last_seen_post`. Keeping names a while *past*
the messages is the point: a thread you have partly pruned still reads as people talking
rather than as hex addresses. Blocked authors' rows are dropped immediately with their
content.

**When pruning runs.**
- **Pruning happens only during a sync**, after a session merges new messages — never
  merely because you added someone to your listen list or favourited/unfavourited
  something. Adding a listen changes *classification* and *future* sync scope, but does not
  itself trigger eviction; the rebalance lands on the next sync. This keeps pruning to one
  well-defined moment and avoids surprising deletions between syncs.
- **Blocking is the one exception**: blocking an identity drops their held content
  immediately, because "I never want to see this person again" landing three days later at
  the next sync is not acceptable behaviour. The fair-share rebalance that the freed space
  implies still waits for the next sync.

**Orphans & fragmentation.**
- Orphans created by pruning are fine (fragmentation is normal). A want is only pursued
  opportunistically and expires by TTL so the device never chases a permanently-gone
  parent forever.

---

## 5. Sync protocol (the heartbeat)

Purposeful, between two present devices over Wi-Fi Direct (or a shared LAN). A session is
**symmetric**: both sides declare what they want *up front*, and each then computes locally
what it owes the other. There is no interactive repair loop and no back-and-forth negotiation
— every exchange is a single offer answered once, never a conversation that converges.

A session has **two phases**: first the **priority phase** (scoped + wanted + thread-bumped
content — this feeds both the **listen** and **context** tiers, the latter via the
thread-bumped stranger-replies), then the **gossip phase** (incidental discovery content →
gossip tier). The gossip phase is always secondary and best-effort. Note the two *sync
phases* don't map one-to-one to the three *storage tiers*: the priority phase's delivered
messages get classified into listen vs context on ingest per §4, and the gossip phase feeds
gossip.

**Contacts vs. strangers.** Sync does **not** require a prior QR exchange — syncing with
someone you've just met is a first-class case, and expected to be mostly gossip-tier
content: a snapshot of a stranger's world. What QR confirmation buys is *contact* status
(knowing which key belongs to which person, so you can listen to them deliberately), not
permission to sync. Because messages are self-verifying, a peer's identity has no bearing
on whether their content is trustworthy; a stranger can only choose *what to offer*, never
forge it. The accepted cost: your listen list is public (§3.3), so **syncing with a stranger
publishes your interests to them** — noted in §9.

**Session outline (symmetric — one declaration round, one reconciliation, two deliveries):**

1. **Connect & version.** Discover peer / establish Wi-Fi Direct. Both sides exchange a
   **protocol-version** byte and agree (MVP: version `1`; mismatch → refuse, tell the user
   to update). Each side displays who it is connected to: for a known contact, their name;
   for a stranger, a short **emoji/word fingerprint** derived from their public key, which
   is also what's compared out loud when confirming an identity in person. Both users
   confirm before any data moves. (Adding that key as a **contact** is the separate QR step
   in §6 — physical presence is what binds a key to a person.)

2. **Declare (both sides, up front).** A and B each send: their **listen-scope** (the
   identities they listen to + the window cutoff as an `effective_time` lower bound) and
   their **want-list** ids. Both declarations are on the table before anything is
   reconciled. (Reminder: listen lists are public in MVP; this is the mechanism.)

3. **Reconcile (each side over its *own* scope).** Each side sends a compact **hash-list**
   of the message-ids it holds (raw 32-byte ids) for the authors *it* declared listening to.
   Each list means one thing: *this is what I already have of what I asked for.* From that
   single exchange each side computes, locally, what the other lacks — because A only ever
   sends B content in **B's** scope, and B's own-scope list describes exactly that space.
   - An earlier draft had both sides list the *union* of both scopes. That is strictly
     larger with no benefit: ids for authors the peer never asked about can never be sent to
     them. Own-scope lists are smaller and leak less — a stranger learns your holdings only
     for authors you already declared listening to.
   - Delivery is filtered to the **receiver's** window, not the sender's and not the wider of
     the two: §4 refuses out-of-window messages on ingest, so sending them is pure waste. (A
     hash-list may still name content past the window, since a starred thread is exempt from
     pruning — that only prevents a re-send, which is the point.)
   - Both deltas exclude blocked-author content (§4) before anything is sent. Blocking is
     enforced twice and independently: the sender drops content from *its* blocklist when
     planning, the receiver drops on *its* own at ingest. Neither list is ever disclosed,
     which is exactly why both ends have to filter.
   - (Naive full hash-list diff is fine at text scale; optimize later only if forced.)

   **Thread-bump cannot be reconciled this way, and needs its own offer/request round.** Its
   whole point is carrying messages from authors in *neither* side's scope — that is what
   makes them stranger-replies — so their ids appear in no hash-list, and a sender has no way
   to tell whether the peer already holds them. Sending blind would re-transmit entire
   threads, which is precisely the waste the reconciliation exists to prevent. So the sender
   **offers** the bump ids, the peer replies with the subset it lacks, and only those are
   streamed. This is the same shape the gossip phase already uses in step 5, so it introduces
   no new idea; an id is 32 bytes against a message of a few hundred, so offering costs about
   a tenth of sending and pays for itself the moment the peer holds any of them.

4. **Deliver (priority phase).** Each side streams its delta — in-scope content the peer
   lacks, the peer's wants it holds, and the bump content the peer asked for after the offer
   in step 3 — **plus the profile records (§3.5) it holds for the authors appearing in that
   delta**. Names ride with content rather than
   getting a phase of their own: receiving somebody's message is exactly when their name
   becomes useful, and it means a name can never be pushed for a key whose content you were
   not already accepting. Thread-bump content is sent
   **newest-first by `effective_time`** and shares the phase's `SESSION_INTAKE_CAP`. There
   is deliberately **no per-root cap** in MVP: a large thread that is *entirely recent* is
   legitimately worth its size, and older tail content (often including the root itself)
   drops off naturally at the window. Revisit only if one thread is observed starving a
   session in M5.

5. **Gossip phase.** A second, lighter exchange for **incidental** content, also symmetric:
   each side offers the ids of its highest-`effective_time` messages **not already covered
   by the priority reconciliation**, bounded by the receiver's gossip-partition budget; each
   replies with which of those ids it lacks; each streams them. Same fair-share rules apply
   on ingest (§4), so incidental content self-limits per identity. This phase is
   skippable/timeboxed — **if the connection drops after the priority phase, the sync is
   still valid and useful.**

6. **Merge & prune.** Each side verifies every received message (verification policy below),
   stores the valid ones, assembles threads, updates its want-list (satisfied → removed; new
   orphan *parents* discovered → added with TTL), then runs pruning (§4) once over the whole
   merged set — evaluating tier reclassification, blocked content, and fair-share together.

**Verification policy.**
- Every received message is checked per §3.2's *Verify on ingest* sequence — strict decode,
  then structural checks, then `sha256 == id`, then signature — in that order, so garbage is
  cheap to discard. §3.2 is the single source of truth for the rules; don't restate them here.
- **A message that fails any check is rejected outright: not stored, not counted against any
  budget, not forwarded, not shown.** There is no `tampered` state in the store.
  - Why not keep and flag them: an unverifiable message's `author` field is by definition
    unauthenticated, so it cannot be attributed to any author's fair share — which makes
    garbage carrying random author keys a cheap way to consume a peer's storage. Nothing
    that can't be attributed is allowed to take up space.
  - The diagnostic value is preserved without the storage cost: rejections are **counted per
    session and per peer**, surfaced in the sync summary ("14 messages from this peer failed
    verification") and logged with the failure reason in debug builds. Bad actors and format
    bugs stay visible; only the bytes are discarded.
- Never trust peer-supplied ordering or timing; recompute `effective_time` and ordering
  locally on ingest.

**Abuse guards.**
- Cap total messages accepted per session per phase at `SESSION_INTAKE_CAP` (1000 to
  start) so a peer can't flood a single sync.
- A peer exceeding `VERIFY_FAIL_CUTOFF` (20) rejected messages in one session has the
  session aborted; content already merged from it stays (it was individually valid).
- The gossip phase is strictly bounded by the local gossip budget regardless of what the
  peer offers.

---

## 6. UI / UX

Keep it calm and honest about partial data. Text-only keeps the surface small.

- **First run:** generate identity → **forced key backup** (recovery phrase / export) →
  set display name → set storage caps (sensible defaults) → **guided first posts**: prompt
  the user to write one or a few opening messages introducing themselves / what they're
  about, and explain the norm to **share early and often**, especially at the start. This
  is the cold-start answer: a brand-new user with an empty network at least arrives at
  their first sync with content to offer, and the "post about yourself" seed gives peers
  something to read and a reason to listen back. Make the empty state encouraging, not
  barren ("Your network is quiet — post something and sync with someone to begin").
- **Two tabs, not one feed.** **My circle** = the listen tier. **Everything else** = the
  gossip tier (discovery; promote an author to your circle from here). **Context-tier**
  messages never appear as standalone items in either tab — they surface inline within
  threads, where they belong, as the stranger-replies that keep your people's conversations
  whole.
- **Both tabs are lists of *threads*, not messages.** Each item is a root (held or not) with
  its recent activity grouped under it, so a burst of replies arriving in one sync reads as
  one updated conversation rather than fifteen scattered lines. Threads sort by their newest
  `effective_time`; messages within a thread sort ascending.
- **Unread is a colour, not a position.** Messages arrive out of order and often stamped
  days back, so sorting alone would bury new content below things already read. Anything not
  yet seen on this device (the local `read` flag, §3.3) is **visually distinct** — the
  freshness cue is the highlight, and time ordering stays honest underneath it. A thread with
  any unread message reads as updated.
- **Thread view:** root at top **if held** — otherwise a calm placeholder ("the start of
  this conversation isn't carried here", showing the short root id), since a long-lived
  thread outliving its root is normal (§3.2), not damage. Replies as a tree where `parent`
  is held, flattened to root with a quiet "replying to a message not carried here" marker
  where not. Never show fragmentation as an error.
- **Compose:** new root, or reply (captures `root` always, `parent` when known). Replying
  is allowed against any `root` id you hold — you need not hold the root message or the rest
  of the thread, and "commenting on gossip whose origin you didn't see" is an intended
  feature, not an edge case.
- **Names:** a petname you assigned always wins and reads as plain text. Any name you did
  not assign is shown as `nickname · a3f1…8e2`, with the fingerprint permanently attached
  and styled as unverified — never as bare text that could be mistaken for an identity.
  Someone with no claimed nickname at all shows as the fingerprint alone. Two visible keys
  claiming the same nickname is expected (§3.1), and the always-on fingerprint is what keeps
  that merely confusing rather than dangerous.
- **Listen:** add/remove identities (via scanned QR of their public key, or from seeing
  them in *everything else*). Public in MVP. Because tiers are recomputed at sync (§4), the
  UI must say what listening actually does — **forward-looking language**: "You'll receive
  <name>'s messages from your next sync onward." Adding someone does not retroactively
  reshuffle what you already hold, and the copy shouldn't imply it will.
- **Block:** available from any message. Confirm plainly what it does — removes their
  messages and the threads they started, immediately and from this device only, and stops
  you passing their content on. Private; the blocked person is never told.
- **Favourite:** pin a message/thread (exempts from pruning).
- **Sync:** an explicit action — find nearby peer, confirm who it is, run session, show a
  simple summary ("received N messages from 3 people you listen to; 40 more from around";
  plus a rejected-message count if any failed verification). Syncing with a stranger is
  offered as normally as syncing with a contact.
- **Add contact:** exchange identities in person via QR code. Separate from syncing — a
  sync doesn't require it (§5).

---

## 7. Technical stack

- **Platform:** fully native Android. **`minSdk` = 33 (Android 13).** Wi-Fi Direct is the
  point of the product, and on API ≤ 32 its discovery requires `ACCESS_FINE_LOCATION` —
  asking for location permission to talk to the phone next to you is exactly the kind of
  thing this app exists not to do. Android 12 and below are out of scope.
- **Language:** Kotlin.
- **UI:** Jetpack Compose.
- **Storage:** Room (SQLite) for the message store and indexes; Android Keystore +
  encrypted export for keys. **Suggested schema/indexes** (drive the hot queries):
  messages table keyed by `id` (primary key, unique — gives dedup for free); a derived
  `thread_root` column (`root` if non-empty else `id`, §3.2) indexed for thread assembly and
  thread-grouped feeds; index on `author` (per-identity fair-share + scope filtering); index
  on `effective_time` (ordering, windowing, oldest-first eviction); composite index on
  (`author`, `effective_time`) for per-identity newest/oldest queries during pruning;
  composite on (`thread_root`, `effective_time`) for the thread-grouped tabs. Local-only
  columns `first_received_time`, `favourite`, `read`, and `tier` live here too (never
  transmitted). Separate small tables for `listen_list`, `blocklist` (pubkey), `blocked_roots`
  (root id), `want_list` (id + TTL/first-seen), and `contacts` (pubkey + display name).
- **Crypto:** Ed25519 (a vetted library, e.g. libsodium binding); content hashing for ids.
- **Transport (MVP): Wi-Fi Direct (Wi-Fi P2P).** Direct device-to-device, no shared
  network or hotspot — the "meet in person and sync" transport. Driven by first-party
  Android APIs (`WifiP2pManager`, `WifiP2pManager.Channel`, a `BroadcastReceiver` on the
  `WIFI_P2P_*` intents). Flow: check `WIFI_P2P_STATE_ENABLED`, `discoverPeers()`, present
  found peers, `connect()` to form a group, then — importantly — **communicate over a
  standard socket** once the group is up. That last point means the byte-stream / framing /
  sync-protocol layer is identical to any other socket transport; only discovery+connect is
  Wi-Fi-Direct-specific.
  - **Permissions:** `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `INTERNET` (sockets), and —
    targeting Android 13+ (API 33+) — the `NEARBY_WIFI_DEVICES` runtime permission with
    `usesPermissionFlags="neverForLocation"` so the app does **not** have to request
    location. (On API ≤ 32, Wi-Fi Direct discovery historically required
    `ACCESS_FINE_LOCATION`; `minSdk` 33 avoids that ugliness — see the platform note above.)
  - **Reliability caveat:** Wi-Fi Direct support and behaviour are per-device/OEM-variable
    and historically flaky. Validate on the actual test devices early (M3). See §9 risk +
    the fallback below.
- **Second transport, built first: same-Wi-Fi-LAN TCP** — both
  devices on one network, discovery via NSD/mDNS (`NsdManager`) or manual IP:port. Reliable
  and trivial; the trade is that peers must share a network (so it can't do "meet anywhere,"
  only "meet where there's shared Wi-Fi"). Useful as (a) a **dependable test rig** that
  isolates protocol bugs from radio bugs, and (b) a real fallback when Wi-Fi Direct fails on
  a given device. Because Wi-Fi Direct also ends in a plain socket, this fallback **shares
  the entire byte-stream/sync layer** — only discovery+connect differs.
- **Architecture seam:** define a `Transport` interface (discover, connect, then exchange
  framed messages over a reliable ordered bidirectional byte stream, close). The sync logic
  talks only to this and must assume nothing about the transport beyond that byte stream —
  no assumptions about latency, bandwidth, or link longevity (BLE/LoRa later are slow and
  flaky; the two-phase sync already respects this by making the priority phase independently
  valid). Concrete transports (Wi-Fi Direct now; same-Wi-Fi TCP fallback; Nearby / BLE /
  Wi-Fi Aware / LoRa later) are swappable without touching the sync brain.

---

## 8. Build phases / milestones

**M0 — Skeleton**
- Project set up (Compose, Room). Data model + **canonical serialization** +
  content-hashing + Ed25519 sign/verify.
- Unit tests: message create/verify round-trips; **id determinism** (same content →
  identical id across runs and across a second independent serialization path);
  empty-`root` root construction and `thread_root` derivation; NFC normalization;
  over-length rejection; ordering tiebreak totality; `effective_time` computation
  (including that a forward-dated message is clamped to its arrival time).
- *Done when:* a message can be created, canonically serialized, hashed, signed, and
  verified, and two separate code paths agree byte-for-byte on the preimage and id.

**M1 — Local single-device app (no networking)**
- Generate identity + forced backup + import.
- Compose, store, and display your own messages. Thread assembly (tree + flatten-to-root).
- Storage caps + **three-tier classification** (listen / context via thread-bump / gossip)
  + per-author fair-share pruning within each partition + favourite exemption + **blocklist
  drop** (author and blocked-root). Verify pruning behaviour with synthetic data.
- *Done when:* with a synthetic corpus exceeding caps, pruning leaves exactly the expected
  survivors (each partition's fair-share respected, favourites retained,
  oldest-by-`effective_time` evicted, redistribution correct, tiers classified correctly
  incl. reclassification when a listened author is added, blocked authors and their rooted
  threads gone including replies that outlive the blocked root), and threads render correctly
  with deliberate gaps — including a thread whose root is not held.

**M1.1 — Names**
- Signed profile records (§3.5): canonical form, nickname validation, sign/verify.
- Nickname chosen during first-run setup; your own profile created and signed.
- Directory table, plus its TTL/listen-immune pruning (§4).
- The display rule everywhere a name appears: petname > `nickname · fingerprint` >
  fingerprint alone.
- *Done when:* a nickname set at first run round-trips through sign/verify; validation
  rejects over-length, unpaired surrogates and bidi-override characters; the directory
  prunes on the rules above with listened authors immune; and no surface anywhere renders a
  claimed nickname without its fingerprint.
- Sync of profiles is **designed here and implemented in M2**, where delivery exists to
  carry them.

**M2 — Sync core, transport-mocked**
- Implement the symmetric declaration round, the single union-scope reconciliation
  (hash-list diff), thread-bump delivery, gossip-phase exchange, want-list handling,
  verification-and-reject, and merge — all behind the `Transport` interface, tested over an
  in-process / loopback mock transport.
- *Done when:* two in-process stores with **divergent but overlapping** message sets, each
  with its own listen list and window, **converge** after one full session to the correct
  expected sets on each side — with: listened-for + thread-bumped content delivered, wants
  resolved where holdable, gossip content delivered within budget, unverifiable messages
  rejected and counted (never stored, never relayed), blocked content neither stored nor
  delivered, caps respected post-merge, and **each id exchanged at most once across the whole
  session** (the union-scope reconciliation, not a second symmetric round, is what makes this
  true — assert it). This milestone proves the protocol logic before any radio is involved.

**M3a — First real transport: same-Wi-Fi-LAN TCP (protocol proof on real devices)**
- Wire the `Transport` interface to plain TCP over a shared Wi-Fi LAN: one peer listens,
  the other connects; mDNS/NSD discovery (manual IP:port fallback). Peer identification
  (contact name or emoji fingerprint) + user confirmation. Protocol-version handshake.
- Purpose: prove the protocol over the **most reliable possible real transport first**, so
  that any bug is unambiguously in the sync logic, not the radio. Also serves as a permanent
  fallback transport and test rig.
- *Done when:* two real devices on the same Wi-Fi network complete a purposeful
  bidirectional sync with the same convergence guarantees as M2, over real TCP — tested both
  between known contacts **and between two devices that have never exchanged QR codes**
  (the stranger-sync path of §5).

**M3b — Primary transport: Wi-Fi Direct (Wi-Fi P2P)**
- Add a second `Transport` implementation using `WifiP2pManager` (discover → connect → form
  group → socket). Reuse the entire byte-stream/sync layer from M3a unchanged (Wi-Fi Direct
  also terminates in a plain socket). `NEARBY_WIFI_DEVICES` permission (target API 33+,
  `neverForLocation`). Handle "Wi-Fi Direct unsupported/disabled" gracefully, offering the
  same-Wi-Fi fallback.
- Purpose: the real "meet anywhere and sync" transport — no shared network required.
- *Done when:* two real devices with no shared network complete the same bidirectional sync
  over a Wi-Fi Direct group; and when Wi-Fi Direct is unavailable on a device, the app
  cleanly falls back to M3a's transport.
- *Validate early on the actual test hardware* — Wi-Fi Direct reliability is OEM-variable
  (§9); discovering a bad device late would be costly. Do this **before M2 is finished**,
  as a throwaway spike if necessary: the answer changes the plan, so it should not arrive
  late.

**M4 — Contacts, listen, discovery surface**
- QR identity exchange (add contact). Listen list management. Blocklist management. The two
  tabs (*my circle* / *everything else*) with thread grouping and unread highlighting;
  promote an author to listen from *everything else*. Favourite from any surface.
  Guided first-run posts + share-early nudges + encouraging empty states.

**M5 — Hardening & field test**
- Verification-rejection edge cases, malformed-peer handling, per-session/per-phase intake
  caps, storage-eviction correctness under churn, key-loss/recovery flow, dropped-mid-
  gossip-phase resilience.
- Real-world test with a few trusted devices. Iterate on the fragmented-thread UX.
- **Watch two things specifically** (both accepted-with-eyes-open in the design): whether
  sybil authors distort fair-share in the gossip partition (§4), and whether a single large
  thread starves a session via unbounded thread-bump (§5).

---

## 9. Key risks & open questions

- **Key recovery is the make-or-break UX.** Losing a key = losing identity. The forced
  backup and a clear recovery path are the single most important non-technical detail.
  (This bit every alternative considered; it does not go away.)
- **Transport reliability (the main transport risk).** Wi-Fi Direct is the MVP's primary
  transport because it's the only common first-party API that syncs two devices with **no
  shared network** — which is the "meet anywhere" behaviour the design is about. But its
  support and behaviour are **per-device/OEM-variable and historically flaky** (discovery
  quirks, occasional connection failures, inconsistent group formation). Mitigations baked
  into the plan: (a) build the reliable **same-Wi-Fi-LAN TCP** transport *first* (M3a) as a
  protocol test rig and permanent fallback, so protocol bugs are never confused with radio
  bugs; (b) validate Wi-Fi Direct on the real test hardware early (M3b); (c) fall back to
  same-Wi-Fi TCP when Wi-Fi Direct is unsupported/disabled on a device. Later radios
  (Nearby Connections — note its **late-2026 change** requiring manual radio-enable and its
  Play-Services dependency; BLE; Wi-Fi Aware — newer, cleaner, but **absent on much
  hardware**; LoRa) all sit behind the same `Transport` interface. One watch-item on the
  TCP fallback: some Wi-Fi networks enable **AP/client isolation** which blocks
  device-to-device traffic — detect and message it rather than failing silently.
- **Reconciliation efficiency** is intentionally naive (full hash-list diff). Fine at
  text/family scale; revisit only if a real dataset makes it hurt.
- **Timestamp trust.** Ordering and windowing use `effective_time`
  (`min(claimed, first_received)`) + id-bytewise tiebreak. This closes forward-dating
  (nothing can claim to be newer than its arrival) and makes backdating self-defeating
  (content only ages itself out sooner). The residual soft spot is that `first_received_time`
  is *per-device* — two devices that first see the same message days apart will order it
  differently, and nothing prevents a peer from sitting on content and releasing it later.
  Convergent global ordering was deliberately traded away for simplicity; the real fix
  (causal / hash-linked ordering) stays post-MVP.
- **Identity is free, so fair-share is sybil-able.** Equal per-author shares assume authors
  are scarce; keypairs aren't. Accepted for MVP to keep gossip open to unknown people (§4),
  with the blocklist as the manual answer and graph-weighted or pooled shares as the
  post-MVP fix. This is the most likely thing to need changing after real use.
- **Stranger sync publishes your listen list.** Listen lists are public in MVP and declared
  at the start of every session, including sessions with people you've never met (§5). Anyone
  who syncs with you learns who you follow. Private/opportunistic listening (§10) is the
  real fix and needs a different, interest-hiding reconciliation — not a UI toggle. Until
  then this should be stated plainly in onboarding rather than buried.
- **Rejecting unverifiable messages hides format bugs behind a counter.** Dropping rather
  than storing them (§5) is the right call for storage safety, but it means a serialization
  mismatch between two builds looks like "peer sent garbage" rather than showing the
  content. The per-peer rejection counter and debug-build logging exist precisely for this;
  keep them loud during M2–M3.
- **No short fingerprint resists a determined impersonator, and it is worth being honest
  about which part actually protects anyone.** Identities are free (§4), so an attacker does
  not break a fingerprint, they grind keys until the *displayed* part matches. Eight hex
  characters is 32 bits — a few minutes on a GPU. A colour is four or five bits: a few dozen
  attempts. Two words from a 2048-word list is twenty-two bits: seconds.

  So colour and fingerprints are **accident-detection**, not defence. They reliably separate
  two people who honestly share a name; they do not stop someone trying to be mistaken for
  you. The thing that actually defends is the **petname** — you confirmed a key in person
  once, and your local name for it is authoritative from then on. The design should keep
  steering people toward QR-confirming the handful of identities they actually care about,
  rather than implying that a fingerprint verified anything.

  A second, subtler effect: **vanity grinding skews the distribution.** If people regenerate
  until they get a colour or word-pair they like, the population clusters in the pleasant
  subspace and accidental collisions get *more* likely — degrading the one thing these
  signals were good at. Partial mitigation, and the reason first-run deliberately shows the
  recovery phrase but never the fingerprint or colour: do not surface the vanity signal while
  rerolling is still free. Once you have posted and been synced, regenerating costs your
  history.
- **Nickname squatting and homoglyphs.** Names are claims, not identifiers (§3.1), so two
  keys claiming `jono` is expected and handled by the always-on fingerprint. What is *not*
  addressed is visual confusability: `jоno` with a Cyrillic `о` renders identically to
  `jono`. Proper detection means Unicode confusable-skeleton matching (UTS #39), which is a
  substantial piece of work and a large data table. Deliberately out of MVP, in the same
  spirit as the sybil hole above — the fingerprint is the mitigation, and it is always
  visible. Revisit if field test shows people reading names and ignoring fingerprints.
- **Thread nesting depth** is a UI concern, deferred deliberately — the data model
  (root backbone + optional parent) supports both flat and nested without change.
- **The empty-network / faint-newcomer problem.** A new person is quiet until someone
  listens to them. Partially addressed by the guided first-run posts and share-early
  nudge (§6) so newcomers arrive with content; onboarding should also nudge an existing
  peer to listen back immediately after an in-person QR exchange.

---

## 10. Deferred (post-MVP), already accommodated by the design

- Background / ambient trusted-peer sync (foreground service; battery-exemption onboarding).
- Private / opportunistic listening (interest-hiding sync), with per-identity hiding —
  and discovery through others' public listens.
- Encrypted DMs (opaque messages carried by listeners who relay you).
- Tag-based listen and complete-graph propagation of tagged roots.
- Additional transports: Nearby Connections, Bluetooth / BLE, Wi-Fi Aware, and the
  LoRa / mesh path — all behind the existing `Transport` interface.
- Efficient set reconciliation (bucketed digests / range-based) if scale demands it.
- Sybil-resistant fair-share: shares weighted by (or restricted to) authors reachable
  through your contact/listen graph, or a single pooled quota shared by all unknown
  authors — replacing the flat per-author split in the gossip partition.
- Adaptive three-tier (listen / context / gossip) partition split — ratio follows the
  user's state (gossip-favoured when the listen list is sparse, tilting toward
  listen/context as it fills, with a permanent gossip floor) — replacing the fixed default.