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
| Framing and record types | **Specified here, not built** |
| Session state machine | **Specified here, not built** |
| Ingest, rejection counting, `PhaseOutcome` | **Specified here, not built** |
| Gossip phase | **Specified here, not built** |
| Transport (mock, TCP, Wi-Fi Direct) | Not started — M2 mock, M3a TCP, M3b Wi-Fi Direct |

---

## 1. Vocabulary

| Term | Meaning |
|---|---|
| **Identity** | An Ed25519 public key, 32 bytes. There is no account behind it. |
| **Scope** | The set of identities a device listens to. Declared to the peer; public in MVP. |
| **Window cutoff** | A lower bound on `effective_time`. A device refuses anything older on ingest. |
| **Hash-list** | The ids a device holds *for authors in its own scope*. |
| **Want** | An id a device is missing and would accept — always a `parent`, never a `root`. |
| **Thread-bump** | Messages sharing a thread with content from an author the peer listens to, written by someone in nobody's scope. |
| **Priority phase** | Wants, in-scope content, bump. Independently valid — a session that stops here succeeded. |
| **Gossip phase** | Incidental recent content for discovery. Best-effort, skippable. |

`effective_time = min(claimed_timestamp, first_received_time)` — always recomputed locally,
never taken from a peer.

---

## 2. Framing

Every record on the stream:

```
[type: u8][length: u32 big-endian][payload: length bytes]
```

- `length` counts the payload only, and **must not exceed `MAX_FRAME_BYTES` (1 MiB)**. A
  larger value is not an error to negotiate — the session aborts immediately. Allocating what
  a peer claims before checking it is how a one-line message becomes a memory exhaustion.
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
| `BUMP_OFFER` | `0x06` | both | ids offered |
| `BUMP_REQUEST` | `0x07` | both | ids wanted from that offer |
| `PHASE_DONE` | `0x08` | both | — |
| `GOSSIP_OFFER` | `0x09` | both | ids offered |
| `GOSSIP_REQUEST` | `0x0A` | both | ids wanted from that offer |
| `SESSION_DONE` | `0x0B` | both | — |
| `ABORT` | `0x0C` | both | reason code (u8) |

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

## 3. Session

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
        │  MESSAGE × n  +  PROFILE × m       │   ── wants, then in-scope, newest first
        │───────────────────────────────────▶│
        │  BUMP_OFFER(ids)                   │
        │───────────────────────────────────▶│
        │◀───────────────────────────────────│  BUMP_REQUEST(subset)
        │  MESSAGE × k                       │
        │───────────────────────────────────▶│
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

## 4. Reconciliation rules

These are implemented and tested in `core/sync/.../Reconciler.kt`.

### 4.1 Building your hash-list

> Every id you hold whose **author is in your own declared scope**.

- **Own scope, not the union of both.** A peer only ever sends you content in *your* scope, so
  ids outside it could never be re-sent to you. Listing them costs bytes and tells a possible
  stranger more about your holdings than they need.
- **Not window-filtered.** A starred thread is exempt from pruning (§4), so you can hold
  content older than your own cutoff. Naming it is exactly what stops a peer re-sending it.

### 4.2 Planning what you owe a peer

Given your holdings, their `SCOPE`, and their `HASHLIST`:

1. **Exclude your blocked content first** — any message whose author or thread root is on
   *your* blocklist. You never relay for someone you blocked.
2. **Wants** — anything they asked for by id that you hold. Not filtered by scope; they named
   it explicitly.
3. **In-scope** — author ∈ their listen set, `effective_time ≥ their cutoff`, id ∉ their
   hash-list, not already planned.
4. **Bump offer** — messages in any thread where you hold a message by an author *they*
   listen to; same window and hash-list filters; not already planned. **Offered, not sent.**
5. **Trim** to `SESSION_INTAKE_CAP` in that order, so truncation drops the least valuable
   first.

Ordering within each group is newest-first by `effective_time`, tie-broken by id bytewise —
the same total order §3.2 fixes for everything else.

**Why bump needs an offer at all.** Its content is written by authors in *neither* side's
scope — that is what makes them stranger-replies — so their ids appear in no hash-list and you
cannot tell whether the peer has them. Offering costs 32 bytes against a message of a few
hundred, and pays for itself the moment they hold any of them.

### 4.3 Answering an offer

> Return the offered ids you do not hold, **in the order offered**, so the sender's
> newest-first priority survives the round trip.

### 4.4 Two invariants, asserted as property tests

- **Nothing is planned that the peer already holds.**
- **Nothing in their scope and window is left behind.**

Converging proves only that nothing was *lost*. These are what prove nothing was sent twice.

---

## 5. Scenarios

Four identities — **Ana**, **Ben**, **Cal**, **Dee** — and two devices, **A** (Ana's) and
**B** (Ben's). `mN(author, thread, time)`.

### 5.1 The ordinary case — friends with overlapping interests

```
A holds   m1(Ben, T1, 1200)   m2(Cal, T2, 1100)   m3(Ana, T1, 1300)
  scope   listen {Ben, Cal}   cutoff 1000

B holds   m1(Ben, T1, 1200)   m4(Ana, T3, 1500)
  scope   listen {Ana}        cutoff 900
```

| Step | Value |
|---|---|
| A's hash-list (own scope: Ben, Cal) | `{m1, m2}` |
| B's hash-list (own scope: Ana) | `{m4}` |
| A → B in-scope | `[m3]` — Ana's, not in B's list, ≥ 900 |
| A → B bump offer | `[m1]` — T1 contains Ana's m3, and m1 is by Ben, in nobody's declared scope here |
| B's reply | `BUMP_REQUEST []` — B already holds m1 |
| B → A anything | nothing: B's only Ben/Cal content is m1, which A's hash-list already showed |

**One message transferred.** Note the offer earning its keep: B's hash-list covers only Ana,
so A genuinely could not know B had m1 — and asking cost 32 bytes instead of re-sending it.

### 5.2 Strangers

```
A  listen {Ben}    B  listen {Dee}
Neither holds anything authored by the other's people.
```

Hash-lists are exchanged, both deltas compute empty, both bump offers empty, `PHASE_DONE`
immediately. **Everything of interest happens in the gossip phase** — which is exactly what
§5 predicts a stranger sync to be: a snapshot of someone else's world.

The cost, stated plainly: A has told B it listens to Ben, and B has told A it listens to Dee.
Listen lists are public in MVP, and syncing with a stranger publishes your interests to them.

### 5.3 Thread-bump, in detail

```
A holds   m1(Ana, T1, 100)   m2(Cal, T1, 110)   m3(Dee, T1, 120)
B holds   m2(Cal, T1, 110)
B scope   listen {Ana}   cutoff 0
```

B's hash-list is **empty** — B holds nothing authored by Ana.

| Step | Value |
|---|---|
| A → B in-scope | `[m1]` — Ana's message |
| A → B bump offer | `[m3, m2]` — both in T1, newest first, neither in B's (empty) hash-list |
| B → A request | `[m3]` — B already holds m2 |
| A → B | `m3` |

B ends with the whole thread. **m2 was never re-sent**, and no hash-list could have prevented
that, because Cal is in nobody's scope.

### 5.4 A want is satisfied

```
A holds a reply whose parent p it does not have  →  A's wants = {p}
B holds p, authored by Cal, whom A does not listen to
```

B's plan puts `p` in **wanted** — delivered despite being outside A's scope, because A named
it by id. A's want-list drops `p` on ingest; had nobody held it, its unsatisfied counter would
increment, and it is dropped after `WANT_TTL` (10) fruitless syncs rather than chased forever.

### 5.5 Blocked content, filtered twice

```
A blocked Cal.        B blocked Dee.
A holds m2(Cal, …)    B listens to Cal.
A holds m3(Dee, …)    B does not block Cal.
```

- A **never sends m2**, though B listens to Cal and asked for exactly that. You do not relay
  for someone you blocked.
- A **does send m3** (Dee), and **B discards it on ingest**, because Dee is on B's blocklist.

Neither blocklist is ever transmitted — they are local and private (§3.3). That is precisely
why both ends filter: the sender cannot know the receiver's list, and vice versa.

### 5.6 Mismatched windows

```
A cutoff 1000     B cutoff 500
```

A sends B anything from `500` onward; B sends A only from `1000` onward. Each side filters to
the **receiver's** cutoff, because §4 refuses out-of-window messages on ingest — sending them
is bandwidth spent on something the peer will drop on arrival.

A's hash-list may still name content older than 1000, if it sits in a starred thread. That is
intended: it prevents a re-send. Starring protects what you already hold; it does not widen
what you will accept.

### 5.7 Over the cap

```
A owes B: 3 wants, 1500 in-scope, 900 bump candidates.   SESSION_INTAKE_CAP = 1000
```

| Group | Sent |
|---|---|
| Wants | all 3 — asked for by id, cheapest and most valuable |
| In-scope | the newest 997 |
| Bump offer | none — budget exhausted |

The remainder is not lost, only deferred: the next session reconciles from the new state. The
sender trims **as courtesy**; the receiver enforces its own cap independently, because a
hostile sender simply ignores this.

### 5.8 The connection drops mid-gossip

Priority phase completed and `PHASE_DONE` was applied. The link then dies.

**The sync succeeded.** Wants were filled, in-scope content arrived, bump was reconciled, and
all of it is persisted. The gossip phase is best-effort by design and its loss costs only
discovery. This is why phases are applied on completion rather than at session end.

### 5.9 A hostile peer

| Behaviour | Response |
|---|---|
| Message with a flipped bit | Rejected: hash mismatch. Counted. Never stored, never relayed. |
| Message signed by the wrong key | Rejected: bad signature. Counted. |
| Profile renamed in transit | Rejected: the claim is signed by the key it names, so a relay cannot edit it. |
| More than `VERIFY_FAIL_CUTOFF` (20) rejections | Session aborted. Content already merged stays — each piece was individually valid. |
| Frame claiming > `MAX_FRAME_BYTES` | Abort before allocating. |
| `MESSAGE` arriving during the `SCOPE` phase | Abort — no record is harmless out of phase. |
| Offering 10 000 gossip ids | Bounded by the *receiver's* gossip budget regardless of what is offered. |
| A flood of unverifiable garbage | Nothing that fails verification consumes storage: an unauthenticated `author` cannot be attributed to any fair share, so it can never take up space. |

Rejections are counted per peer per session and surfaced in the sync summary ("14 messages
from this peer failed verification"). The bytes are discarded; the signal is not.

---

## 6. Constants

| Constant | Value | Where it binds |
|---|---|---|
| `PROTOCOL_VERSION` | 1 | `HELLO`; mismatch refuses |
| `MSG_FORMAT_VERSION` | 1 | first field of every message |
| `SESSION_INTAKE_CAP` | 1000 | per phase, per session, enforced by the receiver |
| `VERIFY_FAIL_CUTOFF` | 20 | rejections from one peer before abort |
| `MAX_FRAME_BYTES` | 1 MiB | checked before allocation |
| `WANT_TTL` | 10 | fruitless syncs before a want is dropped |
| `WINDOW_DEFAULT` | 90 days | user-configurable |
| `NICKNAME_MAX_CHARS` | 32 | after NFC |
| `MSG_MAX_CHARS` | 320 | after NFC |

---

## 7. Open questions

**Does a want override the window?** Wants are currently planned without a window filter — the
peer named the id, so it is sent. But §4 says out-of-window messages are not ingested, so the
receiver may refuse the very thing it asked for. A want is by definition an orphan *parent*,
which is older than the child that made it interesting, so this is the common case rather than
an edge one.

Three ways out, none yet chosen:

1. **Wants override the window on ingest.** You asked for it by id; you get it. The want-list
   is small and TTL-bounded, so the storage exposure is slight. *Recommended* — otherwise the
   want-list is useless for exactly the case it exists to serve.
2. **Wants respect the window**, and the sender filters them like everything else. Consistent,
   but then a want for anything older than the window can never be satisfied and is pure
   round-trip cost until its TTL expires.
3. **Wants are never issued for ids that would be out of window**, moving the decision to
   want-list construction rather than delivery.

**Should the gossip phase have its own cap, separate from the priority phase?** §5 says
`SESSION_INTAKE_CAP` is per phase, so a session can accept up to 2000 messages total. Whether
that is the intent or an accident of wording is worth settling before the phase is built.
