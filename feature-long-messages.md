# Long messages

Expands README item 1 ("Long-form posts via continuation chains"). Driftwood is built for
low-frequency contact: you might sync with someone once a week. That makes each sync worth more,
and 320 characters is a tight budget for "here's what I've been up to". This doc pins down the
chain format, what has to change in storage and rendering, and what stays untouched.

## Why chains, not a bigger message

Raising `MSG_MAX_CHARS` or adding a new record type would be a protocol change: every peer would
need to understand it, and an old client would reject the new form as malformed (counting toward
`VERIFY_FAIL_CUTOFF`). A chain is just ordinary replies. It needs **no protocol change**:
`MessageBody`, `CanonicalCodec`, the wire format and `core/sync` are all untouched. A client that
doesn't understand chains shows a readable run of self-replies, and one that does stitches them
back into one post.

## Chain format

A long post of *n* chunks is *n* ordinary signed messages, all with:

- **Same author.**
- **Same `timestampMillis`.** Every chunk carries the compose time of the whole post. That's what
  makes the chain one post rather than a conversation with yourself.
- **Linked in order:** chunk 1 is a root (or a reply to whatever the post is replying to). Chunk
  *k+1* has `parent = chunk k` and the same `root` as chunk 1.
- **Marker:** chunks 2..*n* start with `->` followed by a space, as README item 1 says. The marker
  counts toward the chunk's 320 characters.

A message is a **continuation** when all of these hold: its text starts with the marker, its
parent is held, and that parent has the same author and the same `timestampMillis`. A message
whose text starts with the marker but whose parent isn't held is a **detached continuation**:
clearly part of something longer, with the start missing. That's a normal fragmentation state, not
an error.

The marker on its own isn't enough. Anyone can type `-> ` at the start of a reply, which is why
the author and timestamp checks are what decide it. The marker is there so a lone fragment is
recognisable, and so non-chain clients show something that reads as "continued".

### Splitting

`MessageFactory` stays as it is; splitting happens in a new pure function in `core/model`
(alongside `MessageText`) so it's testable without Android:

- NFC-normalise first (as `MessageFactory` already does), then split, so chunk lengths are counted
  the same way the receiver will count them.
- Break on whitespace, never mid-word, and never inside a URL (reuse the URL detection
  `LinkifiedText` uses). A single token longer than a chunk is the only case that gets a hard
  split.
- **Cap at 10 chunks** (about 3,100 usable characters). Enforced at compose only. A receiver can't
  enforce it, because a chain is just replies, but the renderer stops stitching after the cap and
  shows the rest as ordinary replies.
- Sign all chunks in one go and insert them in one transaction, so a crash can't leave half a post
  marked unsent.

## Storage: one new derived column

Several queries need to tell chunks apart from replies, and doing it in Kotlin after paging would
break page boundaries (the same argument `feature-local-hiding.md` makes). Add a derived column on
`MessageEntity`, computed at ingest like `threadRoot`/`effectiveTime`:

```kotlin
/** Id of the chain's first chunk when this is a held continuation; null for everything else. */
@ColumnInfo(name = "chain_head") val chainHead: MessageId?
```

- It's set when a continuation's parent is held. If the parent arrives later (a want-list
  backfill), the insert path also updates any held children that now qualify. That's the same
  "late parent fixes up its children" pattern the want-list already relies on.
- It needs a Room migration (v6 → v7) that backfills existing rows. Chains can't exist yet, so in
  practice the backfill is a no-op, but it should still be a real migration, per the "no
  destructive fallback" rule in `DriftwoodDatabase`.

Queries that need it:

- **Feed reply counts** (`reply_count`, `unread_reply_count` and the `known_*` counts in
  `pagedThreads`): exclude rows with `chain_head IS NOT NULL`, or a long post shows up as "6
  replies".
- **Activity** (`pagedActivity`): someone's 5-chunk reply to your post should be one event, not 5.
  Exclude continuations; the event for chunk 1 opens the stitched reply.
- **Search** (`textQuery`): already thread-scoped, so a match in any chunk finds the thread. No
  change needed.

## Pruning: a chain lives or dies whole

This is the one real risk. `Pruner.evictOverQuota` evicts an author's oldest messages one at a
time by `OrderKey(effectiveTime, id)`. Chunks share a claimed timestamp, but `effectiveTime =
min(claimed, first_received)` can differ if chunks arrived on different syncs, and ids tie-break
randomly. So today the pruner could keep chunks 1, 2, 4 and drop 3.

Fix: in `evictOverQuota`, group an author's messages into **units**: a chain is one unit sized by
its chunk count, and anything else is a unit of one. Sort units by their head's `OrderKey` and
evict whole units, oldest first, until the author fits their share. That means `HeldMessage` gains
a `chainHead: MessageId?`. `Pruner` stays a pure function, and `PrunerTest` gets cases for a chain
straddling the share boundary.

A chain still costs its author *n* messages of fair share. That's intended: long posts are paid
for out of your own budget, not anyone else's.

Window eviction (`OUT_OF_WINDOW`) already behaves almost the same for all chunks, since they share
a claimed timestamp. Apply it per unit too, so the rule is consistent.

## Sync: nothing to change, one thing to note

Chains are in-thread replies, so the priority phase already carries them as thread context for
followed authors. The gossip phase's `newestHeld` offer can split a chain across the limit. That
self-heals: a held continuation whose parent is missing goes into the want-list automatically, and
the next sync backfills it. It's worth a `SpecScenariosTest` case to show that, but no code.

## Rendering

- **Thread view:** `ThreadAssembler` folds a continuation into its head's node, and `ThreadNode`
  gains the stitched text (or a list of chunk texts). The stitched post renders as one card, with
  markers stripped and chunks joined by a single space or newline (record which the splitter
  broke on).
- **Replies to a chunk:** people can reply to any chunk (most likely the last one). Show those
  replies under the stitched post, with no special "replying to part 3" treatment.
- **Gaps:** a missing middle chunk renders as an inline "part of this post hasn't reached you yet",
  the same tone as the existing "replying to a message not carried here". A detached continuation
  renders as its own fragment card with "continued from a message not carried here".
- **Feed preview:** `computeThreadPreview` / `truncateForPreview` work on the stitched text, so
  previews read naturally.
- **Compose:** the character counter changes from "x / 320" to "x / ~3,100 · n parts" once the text
  goes past one chunk. The live region from QoL #19 keeps announcing changes.
- **Copy / Share:** act on the stitched text. Long-press on a long post copies the whole post.

## Non-goals

- **No new message version or record type.** Everything here is client-side convention.
- **No editing.** Messages are immutable and signed, and a chain is no different.
- **No chunks from different authors.** "Collaborative" chains are just a thread.

## Open questions

- **Detecting a missing tail.** With a leading marker only, a reader can't tell that chunks 6–7
  never arrived; chunk 5 just looks like the end. Options: a trailing ` ->` on non-final chunks
  too, or a `(k/n)` suffix. Either costs a few characters per chunk and makes non-chain clients
  look noisier. Leaning towards accepting it: an incomplete tail is just the normal "you don't hold
  everything".
- **Local deletion of an unsent long post** must delete every chunk together. `MessageRepository`'s
  unsent-delete should take the chain head and delete by `chain_head`. That's a small change, but
  easy to forget.
- **The 10-chunk cap** is a guess. It's worth checking against what a long post feels like on a
  phone screen before treating it as settled.
