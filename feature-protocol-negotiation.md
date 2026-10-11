# Protocol negotiation

Groundwork for anything that adds to the wire protocol. Profile homepages
(`feature-homepages.md`) are the first; tags, efficient set reconciliation (README item 7), and
anything else that adds a record type will follow. Long messages (`feature-long-messages.md`) don't
need this, because a continuation chain is just ordinary messages.

## The problem

Today the protocol is deliberately strict, and strict in a way that leaves no room to extend it:

- `HELLO` carries a single `protocolVersion` byte, and `Session.handshake` aborts with
  `VERSION_MISMATCH` unless both sides' versions are equal (`Session.kt`, handshake section).
- `FrameCodec.decodePayload` decodes `HELLO` with `exactly(payload, 1 + ID_LENGTH)`, so a longer
  `HELLO` is a `MALFORMED_FRAME` abort.
- An unknown record type is `malformed("unknown record type")`, which also aborts.
- An unknown message or profile *format* version is a `RejectionReason.Malformed`. That counts
  toward `VERIFY_FAIL_CUTOFF`, so 21 records in a new format would tear the session down as
  `TOO_MANY_REJECTIONS`.

Today, then, any addition means two clients on different versions can't sync at all. In a network
that only moves when people meet, that's worse than usual: you can't make everyone update at once,
and a friend on last month's build just becomes unreachable.

The strictness itself is right and should stay: nothing is repaired or guessed at, and a malformed
frame is a hard stop. What's missing is a way for two peers to **agree up front** on what each will
send, so strictness applies to the agreed set rather than to a single fixed version.

## Design: version range plus capabilities, agreed in `HELLO`

New `HELLO` payload:

```
[min_version u8][max_version u8][author 32 bytes][cap_count u8][cap_id u16 × cap_count]
```

- **Version range:** the session runs at `min(max_a, max_b)`, provided that's ≥ `max(min_a, min_b)`.
  Otherwise it aborts with `VERSION_MISMATCH`, as now. Version bumps are reserved for changes to
  framing or session shape, and should be rare.
- **Capabilities:** a sorted list of small numeric feature ids, such as `HOMEPAGES = 1`. The
  session's capability set is the intersection of both lists. Capabilities are for optional,
  additive features: a new record type, a new optional phase, a new message format.
- **Strictness rule:** a record type tied to a capability is only *sent* if that capability is in
  the agreed set. Receiving a record type that wasn't agreed is still `MALFORMED_FRAME`. The decoder
  takes the agreed set as input, so "unknown" means "not agreed this session", not "not in this
  build".
- **Unknown capability ids** in a peer's list are ignored, not rejected. That's what lets an older
  client sync with a newer one: the intersection simply leaves the new feature out.
- **Content formats too:** a capability can also gate which message/profile format versions a
  sender includes. A future `MESSAGE_FORMAT_VERSION = 2` would be sent only to peers that advertise
  it, and the sender filters it out of the hash-list and delivery plan otherwise. That keeps an old
  peer from hitting `VERIFY_FAIL_CUTOFF` over a format it never claimed to understand.

`confirmPeer` still runs right after `HELLO`, before any private data, so nothing about the trust
flow changes.

### Why in `HELLO` and not a separate record

A separate `CAPABILITIES` record after `HELLO` would be one more round trip and one more ordering
rule. Capabilities are needed before the first phase, and `HELLO` is already the one record both
sides swap first. They fit there.

## The one-time break

Today's decoder demands a `HELLO` of exactly 33 bytes and an equal version, so **no v2 design can
be backwards compatible with v1**. A v1 client will always refuse to sync with a v2 client. That's
unavoidable, which is the argument for doing this now: the install base is a handful of test
devices, and this is the change that makes it the *last* flag day.

- Ship it as `PROTOCOL_VERSION = 2` with `min = max = 2` and an empty capability list. That's a
  pure plumbing release with no feature behind it, so it's easy to test across two devices.
- Today an abort shows as `Stopped (version mismatch): …` (`SyncSummaryText.kt`, built from the
  enum name). Give `VERSION_MISMATCH` its own wording, along the lines of "one of you needs to
  update driftwood", since updating is the only thing a user can do about it.
- Homepages then arrive as `max = 2` plus capability `HOMEPAGES`, with no further break.

## Code changes

- `core/sync/Framing.kt`: the `Record.Hello` fields, the encode/decode for the new layout, and
  `decode` taking the agreed capability set (or a `FrameDecoder` instance built after the
  handshake).
- `core/sync/Session.kt`: `handshake` computes and returns the agreed version and capabilities;
  later phases consult them. `Session`'s `protocolVersion` constructor param becomes a supported
  range plus a capability set, so tests can stand up mixed-capability peers.
- New tests in `SessionTest`/`FramingTest`: the intersection, ranges that don't overlap, an
  un-agreed record type rejected as malformed, and unknown capability ids ignored. Most valuable:
  a `ConvergenceTest` pair where one peer has a capability and the other doesn't, showing the
  shared subset still converges.
- `site/undertow.html`: the protocol spec is the source of truth (README), so the `HELLO` layout,
  negotiation rule and capability registry go there in the same PR.

## Capability registry

A table in `undertow.html` (and a Kotlin enum mirroring it) listing every assigned id. It only ever
grows; ids are never reused. Starting entries:

| id | name | adds |
|---|---|---|
| 1 | `HOMEPAGES` | `HOMEPAGE_OFFER` / `HOMEPAGE_REQUEST` / `HOMEPAGE` records — see `feature-homepages.md` |

## Non-goals

- **No negotiation of crypto.** Ed25519 and SHA-256 are fixed, and changing either is a new
  protocol version, not a capability.
- **No transport negotiation.** Iroh/Tor/BLE sit below `Connection` and don't need to know about
  this. A session over any transport runs the same handshake.
- **No downgrade protection beyond intersection.** A peer can always claim fewer capabilities;
  that only costs it features, and nothing it can omit weakens message verification.

## Open questions

- **Width of capability ids.** A `u16` is generous; a bitmask would be smaller but caps the count.
  The list is a few bytes either way, so readability wins.
- **Log the agreed set in `SyncLog`**, so "why didn't their homepage come through?" can be answered
  from Send log / View log. That's cheap and probably worth doing from day one.
