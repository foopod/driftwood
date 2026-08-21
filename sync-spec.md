# Gossip sync protocol — specification

Companion to `plan.md`. That document explains *why* the system is shaped this way; this one
specifies *exactly* what happens on the wire, in enough detail to implement against without
reading the Kotlin.

Everything here is normative unless marked **Open**. Where the two documents disagree,
`plan.md` §5 is the design intent and this is the detail — file a correction against both.

## Implementation status

Honest, because the spec runs ahead of the build in places.

| Part | Status |
|---|---|
| Message canonical form and wire form | **Built**, golden vectors, cross-checked against an independent implementation |
| Profile record (§3.5) | **Built**, tested |
| Reconciliation (`hashList` / `plan` / `request`) | **Built**, 23 tests including two properties |
| Framing and record types | **Built**, 18 tests including two fuzz passes |
| Session state machine | **Built**, handshake through session end |
| Ingest, rejection counting, `PhaseOutcome` | **Built**, tested |
| Gossip phase | **Built**, offer/request/deliver, capped by the receiver |
| Transport (mock, TCP, Wi-Fi Direct) | **Mock built** (bounded pipe, framed connection); M3a TCP, M3b Wi-Fi Direct |

---

## 1. What this is modelled on

The protocol is shaped by how gossip actually behaves between people, not by how a server
would prefer to move rows. Almost every rule below follows from one of these, and the ones
that look arbitrary usually are not.

**Gossip travels because people meet.** Nothing syncs in the background. Two people decide to
sync, in a room, at a moment — so a session is short, interruptible, and worth nothing unless
it is useful the instant it stops. That is why the priority phase is independently valid, and
why phases are applied as they complete rather than at the end. If the bus arrives, what you
already exchanged is yours.

**You hear about people through the people you know.** You do not subscribe to the world; you
follow a handful of people, and *their* conversations bring you everyone else. That is what
context is: a reply from a stranger is worth carrying when it lands in a thread one of
your people is in. Without it you would watch your friends talking to themselves, which is not
a conversation — it is a transcript with half the speakers removed.

**You hear things out of order, and you hear some things never.** A thread whose beginning you
missed is normal. A reply to something you never saw is normal. The software renders those
calmly rather than as errors, because in a gossip network they are not errors. What it does
*not* do is instruct people to write self-contained messages; how much context to include is a
writer's judgement, and they will calibrate it once they feel how impermanent the medium is.

**Gossip is forgotten.** Content ages out of the window and storage is capped. The network
carries a moving present, not an archive. Someone who wants a thing to last has to say so —
that is what starring a thread means, and it is deliberately a personal act rather than a
property of the content.

**Nobody is in charge, so fairness has to come from the shape.** There is no moderator to
appeal to, so a loud account cannot be dealt with by policy — it is dealt with by giving every
identity an equal share of the space, so shouting louder wins nothing. Blocking exists for
what structure cannot fix, and it is deliberately *personal*: local, private, never announced,
never imposed on anyone else.

**Nobody owns a name.** There is no registry, so a nickname is a claim rather than an
identifier, and two people can honestly both be "sam". The only trustworthy name is one you
assigned yourself, to a key you confirmed in person. Everything the protocol does with names
follows from taking that seriously.

**Syncing with someone shows them something about you.** Your listen list is public in MVP,
because declaring it is how the other side knows what to send. The cost is real: sync with a
stranger and they learn who you follow. It is a deliberate trade for MVP, not an oversight.

---

## 2. Vocabulary

| Term | Meaning |
|---|---|
| **Identity** | An Ed25519 public key, 32 bytes. There is no account behind it. |
| **Scope** | The set of identities a device listens to. Declared to the peer; public in MVP. |
| **Window cutoff** | A lower bound on `effective_time`. A device refuses anything older on ingest. |
| **Hash-list** | The ids a device holds *for authors in its own scope*. |
| **Want** | An id a device is missing and would accept — always a `parent`, never a `root`. |
| **Context** | Messages sharing a thread with content from an author the peer listens to, written by someone in nobody's scope. The same word §4 uses for the storage tier they land in — they are the same idea. |
| **Priority phase** | Wants, in-scope content, context. Independently valid — a session that stops here succeeded. |
| **Gossip phase** | Incidental recent content for discovery. Best-effort, skippable. |

`effective_time = min(claimed_timestamp, first_received_time)` — always recomputed locally,
never taken from a peer.

---

## 3. Framing

Every record on the stream:

```
[type: u8][length: u32 big-endian][payload: length bytes]
```

- `length` counts the payload only, and **must not exceed `MAX_FRAME_BYTES` (4 MiB)**. A
  larger value is not an error to negotiate — the session aborts immediately. Allocating what
  a peer claims before checking it is how a one-line message becomes a memory exhaustion.
- The cap is sized by the worst case that has to fit: a hash-list covering a full listen
  partition is 65,536 ids at 32 bytes, or 2 MiB. Four leaves room for the whole store at the
  default budget without chunking.
- **The length is signed.** `0xFFFFFFFF` arrives as `-1`, which passes a naive upper-bound
  check and then asks for a negative-size allocation, so the lower bound is checked too.
- Payload fields use the same encoding discipline as §3.2: `uint16` big-endian length prefix,
  then bytes. Strict decoding throughout — reject, never repair.
- A record arriving **out of phase** aborts the session. There is no state in which an
  unexpected record is harmless.

| Type | Value | Direction | Payload |
|---|---|---|---|
| `HELLO` | `0x01` | both | protocol version (u8) |
| `SCOPE` | `0x02` | both | listen set, window cutoff, wants |
| `HASHLIST` | `0x03` | both | ids held for own scope |
| `MESSAGE` | `0x04` | both | one message wire form, opaque |
| `PROFILE` | `0x05` | both | one profile wire form, opaque |
| `PHASE_DONE` | `0x06` | both | — |
| `GOSSIP_OFFER` | `0x07` | both | ids offered |
| `GOSSIP_REQUEST` | `0x08` | both | ids wanted from that offer |
| `SESSION_DONE` | `0x09` | both | — |
| `ABORT` | `0x0A` | both | reason code (u8) |

Only **gossip** offers before sending. Context does not — see §5.2.

Id lists (`HASHLIST`, `GOSSIP_OFFER`, `GOSSIP_REQUEST`) are bare concatenated 32-byte ids with
no inner count: the frame length already says how many there are, and a second source of truth
is a second thing that can disagree. A payload that is not a whole number of ids is refused.

`MESSAGE` and `PROFILE` payloads are carried **opaquely**: never decoded and re-encoded in
transit. §3.2 requires the receiver to hash the bytes as received, and re-serialising would
silently repair a hostile encoding — normalising away the tampering the hash exists to catch.

### Worked example — one message on the wire

Real bytes, from the committed golden vectors:

```
preimage  000101
          0020 03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8
          0000
          0000
          0008 0000018bcfe56800
          0005 68656c6c6f
```

| Field | Bytes | Meaning |
|---|---|---|
| `v` | `0001` `01` | length 1, version 1 |
| `author` | `0020` `03a1…31b8` | length 32, the signing key |
| `root` | `0000` | **empty — this is a root**; its own id becomes the thread id |
| `parent` | `0000` | empty — targets no specific message |
| `timestamp` | `0008` `0000018bcfe56800` | length 8, 1 700 000 000 000 ms |
| `text` | `0005` `68656c6c6f` | length 5, `hello` |

```
id   = sha256(preimage) = 42162a1482ada7873649a349908c4aef16058d63a8ce859bcb51ea8857ac52bd
sig  = ed25519(preimage, author) = 92a93489…2544e50f
wire = id ‖ sig ‖ preimage      (32 + 64 + variable)
```

A **reply** differs only in that `root` carries 32 bytes and `parent` may too:

```
0020 f0efeeedecebeae9…d2d1     ← root: the thread this belongs to
0020 00070e151c232a…           ← parent: the specific message answered
```

---

## 4. Session

Symmetric in content. One side is *initiator* purely to decide who speaks first; both run the
same state machine. Every exchange is a single offer answered once — never a negotiation that
converges.

```
        A                                    B
        │  HELLO(1)                          │
        │───────────────────────────────────▶│
        │◀───────────────────────────────────│  HELLO(1)
        │                                    │
        │  SCOPE(listen, cutoff, wants)      │   ── declare, both sides, up front
        │───────────────────────────────────▶│
        │◀───────────────────────────────────│  SCOPE(...)
        │                                    │
        │  HASHLIST(ids in A's own scope)    │   ── one exchange, own scope only
        │───────────────────────────────────▶│
        │◀───────────────────────────────────│  HASHLIST(ids in B's own scope)
        │                                    │
        │  MESSAGE × n  +  PROFILE × m       │   ── wants, in-scope, then context
        │───────────────────────────────────▶│      newest first within each
        │  PHASE_DONE                        │   ── priority phase complete: APPLY NOW
        │───────────────────────────────────▶│
        │                                    │      (B mirrors all of the above)
        │  GOSSIP_OFFER(newest ids)          │
        │───────────────────────────────────▶│
        │◀───────────────────────────────────│  GOSSIP_REQUEST(subset)
        │  MESSAGE × j                       │
        │───────────────────────────────────▶│
        │  SESSION_DONE                      │
        │───────────────────────────────────▶│
```

**Each phase is applied on completion, not at session end.** A connection that dies during the
gossip phase must leave the priority phase's results persisted — the priority phase is
independently valid by design, and discarding it would make a partial sync worse than useless.

---

## 5. Reconciliation rules

These are implemented and tested in `core/sync/.../Reconciler.kt`.

### 5.1 Building your hash-list

> Every id you hold whose **author is in your own declared scope**.

- **Own scope, not the union of both.** A peer only ever sends you content in *your* scope, so
  ids outside it could never be re-sent to you. Listing them costs bytes and tells a possible
  stranger more about your holdings than they need.
- **Not window-filtered.** A starred thread is exempt from pruning (§4), so you can hold
  content older than your own cutoff. Naming it is exactly what stops a peer re-sending it.

### 5.2 Planning what you owe a peer

Given your holdings, their `SCOPE`, and their `HASHLIST`:

1. **Exclude your blocked content first** — any message whose author or thread root is on
   *your* blocklist. You never relay for someone you blocked.
2. **Wants** — anything they asked for by id that you hold. Not filtered by scope; they named
   it explicitly.
3. **In-scope** — author ∈ their listen set, `effective_time ≥ their cutoff`, id ∉ their
   hash-list, not already planned.
4. **Context** — messages in any thread where you hold a message by an author *they* listen
   to; same window and hash-list filters; not already planned. **Sent, not offered** — their
   hash-list cannot describe most of it, so some will be duplicates they discard.
5. **Trim** to `SESSION_INTAKE_CAP` in that order, so truncation drops the least valuable
   first.

Ordering within each group is newest-first by `effective_time`, tie-broken by id bytewise —
the same total order §3.2 fixes for everything else.

**Why context is sent blind.** Its authors are in neither side's scope, so no hash-list
describes it and you cannot know what the peer holds. An offer/request round would make it
exact; it is deliberately not used. At the scale this is built for the waste is tiny, and
content-addressing makes a duplicate free to discard:

| Thread size | Peer already holds | Send blind | Offer, then send | Winner |
|---|---|---|---|---|
| 20 | 15 | 6.0 KB | 2.1 KB | offer |
| 20 | 2 | 6.0 KB | 6.0 KB | tie |
| 100 | 80 | 30 KB | 9.2 KB | offer |
| 1000 | 900 | 300 KB | 62 KB | offer |

The offer wins on *bytes* above about 11% overlap — but on a 20-message thread the 4.4 KB it
saves is under 2 ms on Wi-Fi Direct, less than the round trip it costs, and a round trip is
also one more place a flaky link can stall. §9 makes the same call about reconciliation
generally: naive is fine at text scale.

**Revisit when** M5 observes large threads, or a slow radio arrives — over BLE the arithmetic
inverts completely.

**What this gives up:** the context cap becomes *blind*. A sender can spend its whole
allowance on messages the peer already had while ones it lacks go unsent until next time.
Only material at scale, for the same reason.

### 5.3 Answering an offer

> Return the offered ids you do not hold, **in the order offered**, so the sender's
> newest-first priority survives the round trip.

### 5.4 Two invariants, asserted as property tests

- **Nothing is planned that the peer already holds.**
- **Nothing in their scope and window is left behind.**

Converging proves only that nothing was *lost*. These are what prove nothing was sent twice.

---

## 6. Scenarios

Four people — **Alice**, **Bob**, **Carol** and **Dave** — and their phones, **A** and **B**.
Messages are written `mN(author, thread, effective_time)`.

Each scenario states the human situation first, because in most cases the rule only makes
sense once you know what it is protecting.

> **These are executed, not illustrative.** Scenarios 6.1, 6.3, 6.5, 6.6 and 6.7 run against
> the real reconciler in `core/sync/src/test/.../SpecScenariosTest.kt`, asserting the exact
> outcomes printed below. Writing them caught an error in 6.1 that had been hand-waved — it
> moves two messages, not one. A specification whose examples were never run drifts from the
> code silently, and a reader has no way to tell.

### 6.1 The ordinary case — two friends who follow some of the same people

*Alice and Bob are friends. Both follow Carol. They are in the same room and decide to sync.*

```
A (Alice)  holds   m1(Carol, T1, 1200)   m2(Carol, T2, 1100)   m3(Alice, T1, 1300)
           scope   listen {Carol}        cutoff 1000

B (Bob)    holds   m1(Carol, T1, 1200)   m4(Alice, T3, 1500)
           scope   listen {Alice, Carol} cutoff 900
```

| Step | Value |
|---|---|
| A's hash-list — own scope, so Carol only | `{m1, m2}` |
| B's hash-list — own scope, Alice and Carol | `{m1, m4}` |
| A → B in-scope | `[m3, m2]` — newest first: Alice's own message, and the Carol message Bob lacks |
| A → B context offer | `[]` — everything in those threads is already going as in-scope content |
| B → A in-scope | `[]` — Bob's only Carol content is m1, and Alice's hash-list shows she has it |

**Two messages move, both in one direction.** Nothing either side already held is re-sent —
m1 crosses in neither direction, because each hash-list revealed it.

*Why it works out this way:* Bob follows Alice, so Alice's own messages are the point of the
exchange. Carol's messages are equally interesting to both, which is exactly why the
hash-lists prevent them being pushed back and forth every time these two meet — and they meet
often, being friends.

### 6.2 Strangers

*Alice gets talking to Dave at a bus stop. They follow nobody in common — they have never met
before. They sync anyway.*

```
A (Alice)  listen {Carol}      D (Dave)  listen {Erin}
Neither holds anything written by the other's people.
```

Hash-lists are exchanged, both deltas compute empty, both context offers are empty, and
`PHASE_DONE` follows almost immediately. **Everything interesting happens in the gossip
phase** — a slice of recent content from each other's world, which is precisely what a
stranger sync is for.

*The social cost, stated plainly:* Alice has now told Dave that she follows Carol, and Dave
has told Alice he follows Erin. Listen lists are public in MVP because declaring them is the
mechanism, so syncing with a stranger publishes your interests to them. That is a real
trade — someone might reasonably not want a bus-stop acquaintance knowing who they read — and
the fix (interest-hiding sync) is deliberately deferred rather than pretended away.

*Why it still matters:* this is how content reaches people who are not already connected. If
strangers exchanged nothing, the network would be a set of sealed friendship groups and gossip
would never travel.

### 6.3 Context — keeping a conversation whole

*Bob follows Alice but has never heard of Carol or Dave. Alice started a thread, and Carol and
Dave replied to it. Bob's phone has picked up Carol's reply from somewhere, but not Alice's
original and not Dave's.*

```
A (Alice)  holds   m1(Alice, T1, 100)   m2(Carol, T1, 110)   m3(Dave, T1, 120)
B (Bob)    holds   m2(Carol, T1, 110)
           scope   listen {Alice}   cutoff 0
```

B's hash-list is **empty** — Bob holds nothing written by Alice, the only person he follows.

| Step | Value |
|---|---|
| A → B in-scope | `[m1]` — Alice's message; Bob follows her |
| A → B context | `[m3, m2]` — both sit in T1, which contains Alice's message; newest first |
| Bob, on arrival | keeps m3, discards m2 as a duplicate |

Bob ends up with the thread whole. **m2 was sent needlessly**, and no hash-list could have
prevented it, because Carol is in nobody's declared scope. That is accepted: one duplicate
message is a few hundred bytes, content-addressing makes discarding it free, and asking first
would have cost a round trip to save less (§5.2).

*Why context exists at all:* without it Bob receives Alice's message and nothing else, and reads
a conversation with every other voice removed. He would see Alice apparently talking to
herself. Carol and Dave are strangers to Bob, but their words are part of something he
actually follows, so they are worth carrying — as *context*, which is why they land in a
different storage tier from his subscriptions and get evicted first when space runs short.

### 6.4 A want is filled

*Alice's phone holds a reply that answers something she never received. She knows the missing
message's id, because the reply names it as its parent — but not its content.*

```
A's wants = {p}          B holds p, written by Carol
Alice does not follow Carol.
```

B's plan puts `p` in **wanted**, and it is delivered despite being outside Alice's scope,
because she named it by id rather than by author. Her want-list drops `p` on ingest.

*Wants ignore the window, because they cannot do otherwise.* A want is an id and nothing
else — Alice learned it from a `parent` link, so she has no timestamp for `p` and cannot know
whether it falls inside her window until it arrives. Any earlier filter would refuse content
nobody could have known to exclude. So a message satisfying a want is ingested regardless of
age, and then treated like anything else. Most are recent (a parent is rarely much older than
the reply that named it); a genuinely ancient one is accepted and dropped at the next prune,
which costs little and keeps the rule to one sentence.

The prune is where the age finally bites, and starring is the escape hatch. If Alice starred
the thread, the window does not apply to it (§4) and the recovered parent stays; if she did
not, it arrives, completes the thread for that session, and is gone by the next one. That is
the honest limit of the mechanism, and the right one — content older than the window has aged
out of the network by design, and holding it back permanently would undo the bound that keeps
storage finite. A star is how a person says *this thread is the exception*.

*Why wants are opportunistic and not requests:* if nobody Alice meets happens to hold `p`, the
counter increments and the want is dropped after `WANT_TTL` (10) fruitless syncs. The network
is never interrogated and nothing is ever chased. A message that has aged out everywhere is
simply gone, and the design treats that as an ordinary outcome rather than a failure to
recover from.

### 6.5 Blocking — personal, private, and enforced at both ends

*Alice has blocked Carol; she does not want Carol's words on her phone or passing through it.
Bob has blocked Dave. Neither knows about the other's block, and neither ever will.*

| What happens | Why |
|---|---|
| Alice **does not send** Carol's messages, even though Bob follows Carol and they are exactly what he asked for | You do not relay for someone you blocked. Blocking means "not through me", not just "not in my feed". |
| Alice **does send** Dave's messages, innocently | She has no idea Bob blocked him — blocklists are never transmitted |
| Bob **discards them on ingest** | His block is enforced on his own device, where it belongs |

*Why both ends filter:* because neither list travels. Announcing a blocklist would tell the
blocked person they were blocked, and would push one person's judgement onto everyone
downstream. Keeping it private means each device can only enforce its own — so both must.

*The honest limit:* Bob still receives Dave's content over the wire before discarding it, so
blocking saves him from seeing it, not from carrying it briefly. And Alice refusing to relay
Carol costs Bob content he wanted, from someone he has no problem with. That is the price of
blocks being personal, and it is the right price — the alternative is one person's block
silently becoming everyone's.

### 6.6 Mismatched windows

*Alice keeps three months. Bob keeps one — his phone is nearly full.*

```
A cutoff 1000      B cutoff 500
```

Each side filters to the **receiver's** cutoff. Alice sends Bob anything from 500 onward; Bob
sends Alice only from 1000 onward. Sending outside it would be bandwidth spent on something
the peer discards on arrival, since §4 refuses out-of-window messages on ingest.

Alice's hash-list may still name content older than her own cutoff, if it sits in a thread she
starred. That is intended: it prevents a re-send. *Starring protects what you already have; it
does not widen what you will accept.*

### 6.7 More than fits

*Alice has been off the grid for two months and has a lot Bob has not seen.*

```
Owed to Bob: 3 wants, 1500 in-scope, 1500 context candidates.   CONTEXT_SEND_CAP = 1000
```

| Group | Sent | Why |
|---|---|---|
| Wants | all 3 | Named by id; the most precisely useful thing available |
| In-scope | all 1500 | **Uncapped** — see below |
| Context offer | newest 1000 | Bounded |

*Why the priority phase is uncapped.* Every message must verify against the key that signed
it, so a peer cannot manufacture content from people Bob follows. The volume is bounded by
what Alice and Carol actually wrote — which is exactly what Bob asked for by following them.
A peer sending garbage instead does not get further by sending more of it: that is what
`VERIFY_FAIL_CUTOFF` is for. Capping here would throttle the honest case in order to defend
against one a cap does not stop anyway.

*Why context is the exception.* It is written by strangers, into threads that have no size
limit. Someone can flood replies into a thread one of Bob's people is in, and every one of
them verifies, because they genuinely wrote them. Context is the one part of the priority
phase whose volume Bob did not choose, so it is the one part that stays bounded.

Nothing is lost, only deferred: the next time they meet, reconciliation starts from the new
state. The sender trims **as a courtesy**; the receiver enforces its own limits regardless,
because a hostile sender simply ignores this.

*Why newest-first:* in a medium that forgets, the recent is what people are still talking
about. Older content is more likely to have aged out on the far side anyway.

### 6.8 The bus arrives

*Alice and Bob are mid-sync when Bob has to leave. The connection dies during the gossip
phase.*

**The sync succeeded.** `PHASE_DONE` for the priority phase had already been applied: wants
filled, followed content delivered, context reconciled, all persisted. The gossip phase is
best-effort and its loss costs only discovery.

*Why phases are applied as they complete:* two people syncing in the world get interrupted.
Holding everything until a clean finish would mean a sync that is 90% done is worth exactly
nothing, which is the wrong shape for a protocol whose whole premise is brief encounters.

### 6.9 Someone trying it on

*Bob's phone is talking to a peer that is not behaving.*

| Behaviour | Response |
|---|---|
| A message with a flipped bit | Rejected: the hash does not match the id. Counted. Never stored, never relayed. |
| A message signed by the wrong key | Rejected: bad signature. Counted. |
| A profile renamed in transit | Rejected: the claim is signed by the key it names, so a relay can withhold a name but never edit one. |
| More than `VERIFY_FAIL_CUTOFF` (20) rejections | Session aborted. Content already merged stays — each piece was individually valid. |
| A frame claiming more than `MAX_FRAME_BYTES` | Abort *before* allocating. |
| `MESSAGE` arriving during the `SCOPE` phase | Abort — no record is harmless out of phase. |
| Offering 10 000 gossip ids | Bounded by the *receiver's* gossip budget, regardless of what is offered. |
| A flood of unverifiable garbage | Nothing that fails verification consumes storage. An unauthenticated `author` cannot be attributed to anyone's fair share, so it can never take up space. |

*Why rejections are counted rather than just dropped:* the bytes are worthless but the signal
is not. "14 messages from this peer failed verification" is the difference between noticing a
bad actor and noticing nothing. It is also how a format bug between two builds shows up as
something other than silence.

*What none of this defends against:* someone standing in front of you claiming to be someone
else. Identities are free, so a short fingerprint can be ground to match in minutes and a
colour in a few dozen attempts. The only real answer is a petname — a name you assigned to a
key you confirmed in person — which is why the design keeps steering people toward doing that
for the handful of identities they actually care about.

## 7. Constants

| Constant | Value | Where it binds |
|---|---|---|
| `PROTOCOL_VERSION` | 1 | `HELLO`; mismatch refuses |
| `MSG_FORMAT_VERSION` | 1 | first field of every message |
| `CONTEXT_SEND_CAP` | 1000 | context sent per session |
| `GOSSIP_INTAKE_CAP` | 1000 | gossip accepted per session; wants and in-scope are uncapped |
| `VERIFY_FAIL_CUTOFF` | 20 | rejections from one peer before abort |
| `MAX_FRAME_BYTES` | 4 MiB | checked, both bounds, before allocation |
| `WANT_TTL` | 10 | fruitless syncs before a want is dropped |
| `WINDOW_DEFAULT` | 90 days | user-configurable |
| `NICKNAME_MAX_CHARS` | 32 | after NFC |
| `MSG_MAX_CHARS` | 320 | after NFC |

---

## 8. Open questions

Both questions this document opened with are now settled, and the reasoning is recorded above
rather than here: wants ignore the window because they cannot be filtered (§6.4), and the
intake cap applies to context and gossip rather than to the whole phase (§6.7).

What remains open is smaller and belongs to later milestones:

- **Whether one thread can starve a session.** Context is capped per session, but there is
  still no per-thread cap (§5), so a single very busy thread can fill the whole context
  allowance. Deliberate for MVP — a large thread that is entirely recent is legitimately worth
  its size — and worth revisiting if M5 observes it happening.
- **Hash-list size on slow radios.** 2 MiB worst case is nothing over Wi-Fi Direct and minutes
  over BLE. Post-MVP, with bucketed digests already named as the fix (§10).
