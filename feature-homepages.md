# Profile homepages

A small, personal page attached to an identity: a few paragraphs about yourself, links you want
to share, maybe pointers to your favourite posts. It extends the profile from "a name and a
fingerprint" to a little space you curate and others carry. Like long messages
(`feature-long-messages.md`), it suits low-frequency contact: when you only sync with someone
every few weeks, their homepage is where they say what's going on in their life.

Depends on `feature-protocol-negotiation.md`: homepages are a new record type behind the
`HOMEPAGES` capability.

## Format: Markdown, not HTML

The page body is Markdown, drawn natively in Compose. HTML was considered and rejected:

- **It phones home.** One `<img src="https://…">` and opening someone's homepage contacts a server,
  leaking your IP and the time you looked. That silently breaks "nothing phones home" for a reader
  who never chose to leave the device-to-device model, which is the same reasoning that made link
  previews opt-in per tap (`feature-qol.md`).
- **It's a lot of attack surface.** Untrusted HTML means a WebView with JavaScript off, network
  blocked and a strict sanitiser, all of which must stay correct forever. That's a big addition to
  a text-first app.
- **Markdown is enough.** Headings, emphasis, lists, quotes, links and a little theming (below)
  give people a space that feels like theirs, with nothing that can reach the network on its own.

### Supported subset (v1)

CommonMark, minus anything that renders external content:

- **Rendered:** paragraphs, headings, emphasis/strong, ordered and unordered lists, block quotes,
  inline code and code blocks, horizontal rules, links.
- **Shown as plain text:** raw HTML (escaped, never interpreted) and image syntax
  (`![alt](url)` renders as its alt text plus the link, never fetched).
- **External links** (`http`/`https`) behave like links in messages: tap to open in the browser,
  and long-press for Copy/Open (QoL #9). Nothing loads until tapped.
- **Internal links** use a `driftwood:` scheme and resolve locally:
  - `driftwood:msg/<hex id>` opens that thread.
  - `driftwood:author/<hex id>` opens that contact (or "someone you haven't met").
  - A link to something not held shows the usual "not carried here" state.

  This is what lets a homepage work as a curated index of your own posts.

A Compose-native Markdown renderer is the expected route. `commonmark-java` with HTML disabled is a
pure-JVM parser that fits; whether to render its AST ourselves or use an existing Compose library
is a build-time decision.

### Frontmatter for theme information

A homepage may open with a frontmatter block, a fenced `---` section at the very top, to carry
presentation hints alongside the content:

```markdown
---
accent: #3a7bd5
---
# Hi, I'm Sam

Mostly posting about boats and sourdough.
```

What the keys actually are (accent colour, a background tint, a choice of a few typefaces or
preset themes) **isn't decided and doesn't need to be until we build it**. The container rules can
be fixed now, so pages written early don't break later:

- **Flat `key: value` lines only, not YAML.** Full YAML is a large parser with a history of
  surprises (anchors, type coercion, billion-laughs). A line-based parser is all theme hints need.
- **Unknown keys are ignored. Invalid values are ignored.** A page written for a future client
  renders fine on today's, just with fewer flourishes. A homepage is never rejected over its
  frontmatter.
- **The reader's legibility wins.** Any colour hint is applied by the renderer, which is free to
  adjust or drop it to keep contrast in light and dark mode. An author can tint their page but
  can't make it unreadable.
- **Frontmatter counts toward the size cap**, the same as the body.
- **Frontmatter is never displayed** as text; a client that doesn't understand it strips it.

## The record

A homepage is its own signed record, **separate from `Profile`**. The profile is resent with every
batch of an author's messages (`Session.deliverBatch`), which is fine for a username and wasteful
for a page of up to 16 KiB. Keeping them apart also means `Profile`'s format and the `directory`
table don't change at all.

```
HOMEPAGE wire form: sig (64) || preimage
preimage (same length-prefixed field style as ProfileCodec):
  version u8 | author 32 bytes | timestamp u64 | body (UTF-8, NFC)
```

- **Keyed by author, latest claimed timestamp wins**, exactly like `Profile`. There's one current
  homepage per identity, and older ones are dropped on replacement.
- **Its hash** is the SHA-256 of the preimage, used to offer and request without sending bodies.
- **Size cap:** the body is at most 16 KiB of UTF-8. That's enough for a real page of text, and
  small enough that a few hundred homepages stay in the low megabytes.
- **Verification** follows `ProfileCodec.verify`: structure, then size, strict UTF-8 and NFC, then
  the signature. Markdown validity is *not* checked. The envelope is strict and the content is
  rendered leniently, because a page that renders oddly is a display problem, not a protocol
  violation. Bad signatures count toward `VERIFY_FAIL_CUTOFF` like any other rejection.

## Sync

Only when `HOMEPAGES` is in the agreed capability set. It's a short offer/request exchange after
the priority phase, the same shape as the existing gossip offer/request:

1. **`HOMEPAGE_OFFER`**: a list of `(author, timestamp, hash)` for homepages I hold that you'd want.
   That's every author in your declared follow scope, **plus my own homepage**, always offered.
   Sharing your own space with whoever you meet is the point.
2. **`HOMEPAGE_REQUEST`**: the hashes you don't hold and whose timestamp is newer than what you
   have for that author.
3. **`HOMEPAGE`** records for what was requested.

Both directions run this exchange. Neither side sends a body the other already holds, so a sync
with nobody's homepage changed costs a few dozen bytes per author. Homepages from blocked authors
are neither offered nor accepted, the same as their messages and profiles.

Strangers' homepages don't travel in the gossip phase in v1. See Open questions.

## Storage

A new `homepages` table, outside the three message tiers. It needs a Room migration (the next
version after whatever long messages takes):

```kotlin
@Entity(tableName = "homepages")
internal class HomepageEntity(
    @PrimaryKey val author: AuthorId,
    @ColumnInfo(name = "claimed_at") val claimedAt: Long,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    /** The signed record itself, relayed onward unchanged — same as DirectoryEntity.record. */
    val record: ByteArray,
)
```

- **Kept for:** yourself, authors you follow, and contacts. On each post-sync prune, drop
  homepages for anyone outside that set. There's no fair-share maths, because the set is bounded by
  your own social graph.
- **Blocking** deletes that author's homepage straight away, the same as their messages.
- A homepage survives its author's messages being pruned. It's tied to the person, not the window.

## UI

- **Viewing:** a "Homepage" section on `ContactScreen`, opening a full-screen rendered page with
  the author's name, fingerprint and "updated 3 days ago" (`RelativeTime`). If no homepage is held,
  there's no section at all, not an empty state.
- **Editing your own:** in Settings, a plain-text editor with a byte counter toward 16 KiB and a
  Preview toggle that uses the same renderer readers will see. Saving signs a new record, and it
  goes out on your next sync.
- **Discoverability:** a small marker on an author's name (in thread view and contacts) when they
  have a homepage, so people know there's something to open.

## Non-goals

- **No HTML, scripts, or remote resources** of any kind, per the format section.
- **No version history.** Latest wins; an old homepage isn't kept or offered.
- **No comments or replies on a homepage.** Conversation happens in posts. A homepage can link to
  a thread with `driftwood:msg/…` if the author wants one.

## Open questions

- **Images.** The obvious next ask. Inline images carried in the record (not fetched) would keep
  "nothing phones home", but even one small image dwarfs the text budget. Leave it until there's a
  storage story for binary content generally.
- **Homepages in gossip.** Offering strangers' homepages in the gossip phase would help discovery,
  but brings back the spam question this design sidesteps by staying within your graph. Revisit
  with field data.
- **Future-dated timestamps.** "Latest claimed timestamp wins" lets an author pin a homepage by
  claiming a far-future time, so later edits never replace it. The same weakness exists for
  `Profile` today. Worth fixing for both at once, for example by refusing claims more than a day
  ahead of the receiver's clock.
- **Theme keys.** Deliberately left open (see Frontmatter). Decide when building, starting from
  what can stay legible in both themes.
