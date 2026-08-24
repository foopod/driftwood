# Protocol criticism

A critical look at the sync protocol's actual design and implementation — not the docs' prose,
the real Kotlin — prompted by two questions: are "wants" actually worth what they cost, and are
there features worth adding now, before `undertow` (the sync protocol + data model) ships as a
standalone library other people implement against. Every code claim below is sourced to an
exact file and line, checked directly against the repository rather than the design docs, so
this document is falsifiable rather than a restatement of `plan.md`.

## Summary

- **Wants work, but nobody can see them working.** The mechanism is real and correctly built —
  yet it exposes zero information to the UI, the sync summary, or even a log line. It's
  impossible to know today whether it's actually helping.
- **A want, once created, permanently bypasses the window/aging filter for that one message** —
  by necessity, not by oversight, but the consequence is a real, narrow way to resurrect
  specifically-chosen old content past the "moving present, not an archive" guarantee.
- **The bigger gap isn't wants — it's that nobody can find their first peer.** Neither `plan.md`
  nor `sync-spec.md` addresses how a solo installer with no existing contacts ever meets anyone
  to sync with at all. That's a different, more consequential problem than the one the docs do
  solve ("what do I post once I've met someone").
- **Mentions, reactions, and message editing/deletion are never discussed anywhere** — not
  deferred, not rejected, absent. Tags and DMs are deferred, each in one line.
- **DMs, as currently described, now conflict with what the website promises.** `plan.md` calls
  DMs "architecture accommodates; not built" — but the architecture has no concept of routing a
  message to one specific person, which is the entire point of a DM. And `index.html` now
  states outright: *"there's no direct messaging or private groups."*

---

## 1. Wants: working, but invisible

A want is created exactly the way you'd guess: never proactively, only as a side effect of
receiving a message whose `parent` field points at an id you don't hold. You never learn about
a want in the abstract — you learn about it because something you *did* receive told you it
exists. That's `RoomSyncStore.updateWants()`
(`core/data/src/main/kotlin/com/jonoshields/gossip/core/data/RoomSyncStore.kt:132-157`), called
once per applied phase, inside the same transaction as the messages it's reacting to:

```kotlin
val orphans = outcome.accepted
    .mapNotNull { it.body.parent }
    .filterNot { it in arrived }
    .distinct()
    .filter { messages.find(it) == null }
wantList.add(orphans.map { WantEntity(it, nowMillis, unsatisfiedSyncs = 0) })

wantList.ageAll()
wantList.dropExpired(WANT_TTL)
```

`WANT_TTL = 10` (`core/sync/src/main/kotlin/com/jonoshields/gossip/core/sync/Reconciler.kt:35`)
— ten fruitless syncs and a want is quietly forgotten. The doc comment on `updateWants` is
honest about the design intent: *"A parent nobody we meet happens to hold is not chased; after
[WANT_TTL] attempts it is simply forgotten, because content that has aged out of the network
everywhere is gone, and the design treats that as an ordinary outcome rather than a failure."*
That's a reasonable philosophy. The problem is it's untestable from where anyone actually
stands today:

- `SyncSummary` (`core/sync/.../Session.kt:44-59`) — the struct the UI's sync-result screen is
  built from — has fields for messages sent/accepted, profiles, rejections, blocked/stale/over-
  budget drops, and phase completion. **No field for wants added, wants filled, or wants
  expired.**
- `grep -rin "want" app/src/main/java/com/jonoshields/gossip` returns nothing. The entire
  app/UI module has zero awareness that this system exists.
- There is no log statement anywhere in `Session.kt` or `RoomSyncStore.kt` — not even at debug
  level. Compare this to `plan.md` §9's explicit stated intent for the verification-rejection
  path: *"The per-peer rejection counter and debug-build logging exist precisely for this; keep
  them loud during M2–M3."* Wants never got the equivalent treatment.

**So: are wants worth it?** The honest answer is nobody can currently say, because the one
number that would answer the question — how often does a want actually get filled, versus
quietly expiring after ten syncs — isn't captured anywhere. The mechanism itself is cheap by
design (`Reconciler.kt` treats wants as unconditional, uncapped sends — no dedicated round trip,
they just ride along on syncs that are happening for other reasons), so "rip it out" isn't
obviously the right call either. The actual recommendation: **instrument it before deciding
anything.** Add wants-added/wants-filled/wants-expired to `SyncSummary`, surface it somewhere —
even just the Storage screen — and let real usage answer the question the code currently can't.

## 2. A sharper question: can a want resurrect old content?

Once a want exists, it does something worth naming precisely. From `Ingest.offerMessage`
(`core/sync/.../Session.kt:463-482`):

```kotlin
// effective_time is min(claimed, received); received is now, so a message can only be
// out of window by claiming to be old.
if (message.id !in wants && message.body.timestampMillis < windowCutoff) {
    staleDropped++
    return
}
```

The doc comment above this class explains why the bypass exists, and it's a real structural
necessity, not an oversight: *"A want is an id with no timestamp attached, so its age cannot be
known until it arrives; refusing it here would turn down the very thing we asked for"*
(`Session.kt:442-444`). Fair — you can't pre-filter something by a property you don't have yet.

But look at the actual boolean: `message.id !in wants && ...`. Once a message's id is in the
want set, this check is skipped **unconditionally** — including after its real, claimed
timestamp is known. A message you have a want for is never rejected for being stale, no matter
how old it turns out to be. That's a permanent, per-message exception to the window model, not
just a "can't pre-check" workaround.

And a want is trivial to manufacture for a chosen target: author a single fresh reply (any
recent timestamp, passes ingest normally) with `parent` set to the id of whatever old message
you want resurrected, and get it to one person who'll relay it onward. Every device that
receives that reply now wants the ancient parent — and if *anyone* still holds it (the original
author, kept as a favourite, whatever), it will be delivered and admitted with no age check at
all, to every device downstream that received the reply.

The blast radius is narrow — this only resurrects content someone deliberately chose to keep
and can be reached to fetch, not a wholesale defeat of the aging model — but it's a real,
previously undocumented edge case, and it sits directly against a claim the site now makes
publicly (`index.html`: *"the network carries a moving present, not an archive"*). Worth an
explicit decision: is this acceptable as designed, or does a want-fulfilled message need its own
window check after all (accepting that this means some wants can never be filled even when the
content exists)?

## 3. The real gap: cold-start has no answer

`plan.md` §6 and §9 solve a real problem: a brand-new user with an empty network has nothing to
say and nothing to show. The guided first-run posts and "share early and often" nudge exist
specifically for this (`plan.md:621-628`), and it's a good answer to that specific problem.

But it's the *wrong* problem, or at least not the first one. Before "what do I post," a new user
needs "who do I even meet." Search both documents and there is no seed-peer list, no directory,
no rendezvous mechanism, no bootstrap concept of any kind — not deferred, not rejected, just
never raised. Discovery is scoped strictly to physical proximity from the start (`plan.md:44-46`:
*"Purposeful sync between two present devices over Wi-Fi Direct... this is the 'meet in person
and sync' transport the whole design is about"*), and adding a contact means an in-person QR
exchange (`plan.md:671`).

That means: a solo installer who doesn't already personally know another user of this app has
**no path into the network at all** — not a slow one, none. And two friends who both install it
but never happen to be physically in the same room can't bootstrap each other either. This is a
harder problem than the empty-network-has-nothing-to-say one, and it's the one that actually
determines whether this thing can grow past a single friend group that already meets in person
regularly.

This isn't necessarily a bug — "friends-and-family, in-person-first" is a coherent, legitimate
product shape, genuinely different from "grow an open network," and might be exactly what's
intended. But it's worth being a deliberate decision rather than an unexamined one. If some
easing is wanted, the option that doesn't compromise the "no servers" identity: let someone
export/share their public key or QR through *any* out-of-band channel — text, email, whatever —
before physically meeting, so the identity-confirmation half of the first sync can happen ahead
of time and the in-person moment is a quick "yes, still you" rather than the entire first
contact. That's still zero infrastructure and zero directory — just making the thing you already
do in person (§6's QR exchange) shareable a step earlier.

## 4. Mentions — the one feature where timing genuinely matters

Not discussed anywhere in `plan.md` or `sync-spec.md`. The current `MessageBody`
(`core/model/src/main/kotlin/com/jonoshields/gossip/core/model/MessageBody.kt:23-30`) is exactly
`version, author, root, parent, timestampMillis, text` — no field for it.

The case for doing something about this *now* specifically: a mention that's actually reliable —
survives a rename, can't be spoofed by someone else claiming the same display name — has to
reference the mentioned person's real key, not their username text. That means a new field in
the canonical preimage: a wire-format change. Today that's an internal refactor. Once `undertow`
ships as a library other people implement against (the M5 step just added to `plan.md`), the
exact same change becomes a breaking change for every external implementation. The cost of this
specific change goes up sharply at a fixed, known point in the roadmap — that's the actual
argument for acting now, not "mentions are important."

Recommendation: reserve a small, bounded field for mentioned `AuthorId`s in the message body now
— no UI, no notification logic, nothing built on top of it yet — purely to avoid paying the
library-compatibility cost later. This is a deliberate, narrow exception to "don't build for
hypothetical requirements," justified specifically by wire-format lock-in timing, not by the
feature itself.

## 5. Tags — no urgency, and two different features are being conflated

`plan.md` §10 defers *"tag-based listen and complete-graph propagation of tagged roots"* — but
that's a specific, ambitious feature (tags as a second dimension of listen scope, with their own
propagation and budget rules alongside author-based listening), not "hashtags" in the ordinary
sense. Plain hashtags — `#word` inside the existing `text` field, indexed and filtered
client-side — need **zero protocol change** and can be added at any time, including after
`undertow` ships as a library, with no compatibility cost at all.

Recommendation: no action needed now. Don't let the correctly-deferred, genuinely-hard version
(tag-based propagation as a new scope primitive) create false urgency around the trivial version
(client-side hashtag parsing), which was never actually blocked on anything.

## 6. DMs — the "architecture accommodates" claim deserves scrutiny

`plan.md` §2 and §10 both describe DMs as already accommodated: *"DMs / encrypted messages
(architecture accommodates; not built)"* and *"Encrypted DMs (opaque messages carried by
listeners who relay you)."*

That undersells what's actually missing. Every propagation concept that exists today — listen,
context, gossip — is symmetric and author-based: "carry what this author's followers want,"
never "get this specific item to person X." An encrypted payload could ride inside the existing
opaque `MESSAGE` frame without much trouble, sure. But actually routing it toward one intended
recipient, through strangers who by design can't read it and today have no reason to
preferentially carry anything toward anyone, needs a targeted-delivery primitive that doesn't
exist yet. That's a new piece of the propagation model, not a detail sitting on top of the
existing one — "architecture accommodates" is optimistic about how much of the hard part is
already done.

This also isn't just a technical footnote anymore. `site/index.html` now says, as a headline
positioning statement: *"Which makes this a **public** network, not a private one — there's no
direct messaging or private groups. Only post what you're happy to see travel."* That's live,
public-facing, and unqualified. `plan.md` still lists DMs as an accommodated future feature. Both
can't stay true without a caveat neither currently has.

Recommendation: pick one, explicitly. Either drop DMs from `plan.md`'s deferred/accommodated
list to match what's now publicly promised, or keep them as a real possibility but be upfront
that they'd need a genuinely new routing mechanism and should be positioned as a distinct,
secondary mode — never something that quietly rides in on the existing architecture, since it
doesn't.

## 7. Reactions/likes — probably a deliberate absence, worth saying so out loud

Not discussed anywhere in either document — no mention, deferred or otherwise. This one might
not be an oversight. `plan.md` §1's guiding principles are explicit about designing from the
medium's actual properties rather than importing centralized-social-media conventions, and a
visible reaction or like count is exactly that kind of import — the same instinct that already
produced "no follower counts, no like counts" as an implicit design stance throughout the rest
of the docs.

Recommendation: don't add reactions, but say so explicitly rather than leaving it as unaddressed
silence — a future reader (or contributor) shouldn't have to guess whether this was decided or
just never came up. If lightweight reactions are ever wanted anyway, the existing `Favourite`
(currently local-only, never gossiped) is the nearest primitive — turning it into a signed,
propagated reaction would be a real scope expansion, not a small tack-on, and deserves its own
discussion rather than sneaking in as "just a like button."

## 8. Edit/delete — already correctly decided, not a gap

Included for completeness, since it was implicit in "etc." Messages are content-addressed —
changing the text changes the id, so there is no such thing as "editing" a message, only ever
authoring a new one. Permanent deletion is explicitly named in `plan.md` §1 as a guarantee the
medium can't keep. Nothing to add here; this is a foundational, already-correct decision, not an
open question.
