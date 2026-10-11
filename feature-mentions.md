# Mentions

Expands README item 2 ("Planned, not yet implemented"). Referencing another identity from
within a message. Picked up now (ahead of the other README "Planned" items) because Activity
already gives mentions a natural home: right now Activity means "replies to messages you wrote";
a mention is the second, symmetric way of being addressed, "you replied to me" vs "you mentioned
me," surfacing in the same list.

## Syntax: `@[<authorHex>]`

A mention is literal text embedded in a message: `@[` + the mentioned identity's 64-hex-char
`AuthorId` (`AuthorId.toHex()`/`fromHex()`, `Ids.kt`) + `]`. Not a username — usernames are
just claims anyone can make (Settings' own copy: "Anyone can claim any username"), so a
name-based `@alice` would be spoofable and ambiguous. Keying on the identity hash matches how
the rest of the app already treats identity as ground truth (`Contact(authorHex)`,
`AuthorId.toHex()` throughout).

No new record type, no wire-format change: a mention is ordinary message text, counted against
the existing `MSG_MAX_CHARS = 320` budget (`MessageBody.kt`) exactly like any other character —
`@[` + 64 hex chars + `]` is 67 code points per mention, so it's a real but bounded cost, not
something worth optimizing for v1.

## Explicitly no sync/tiering effect

A mention has **zero** effect on delivery, fair-share, or tiering — it's ordinary message
content and nothing about `core/model`/`core/sync`/`core/store` needs to change. This is a
deliberate scope decision, not an oversight: the alternative (mentions boosting delivery
priority or fair-share) is a real design space but a separate, much bigger discussion about
gaming incentives, and isn't needed to deliver the actual feature (letting people reference each
other, and surfacing that in Activity).

## Parsing: shared, in `core/model`

Both the render-time linkifier (UI) and the insert-time indexer (data layer, below) need the
same "find valid mention tokens in this text" logic, so it belongs in one place rather than two:
a `MentionParser` (or similar) in `core/model`, alongside `MessageText`, exposing something like

```kotlin
object MentionParser {
    fun findAll(text: String): List<Mention> // start, end, AuthorId
}
```

Scans for `@\[([0-9a-fA-F]{64})\]`, and for each match tries `AuthorId.fromHex(hex)` — a token
that matches the regex but somehow fails to decode (shouldn't happen given the regex, but
`Hex.decode`/`AuthorId.of` are the actual authority) is simply not treated as a mention, never a
parse error. Text a user typed that merely *looks* like a mention but isn't valid is left as
plain text — parsing must never throw on arbitrary message content.

## Data model: a derived index, not new protocol state

```kotlin
/** Every valid @[authorHex] mention found in a held message's text, derived locally at insert
 * time — never transmitted, never part of the signed content, purely a local query accelerant.
 * Two devices holding the same message independently derive identical rows. */
@Entity(
    tableName = "mentions",
    primaryKeys = ["message_id", "author"],
    indices = [Index("author")], // the direction Activity queries by
)
internal class MentionEntity(
    @ColumnInfo(name = "message_id") val messageId: MessageId,
    val author: AuthorId,
)
```

Populated wherever a message is currently inserted — both `RoomMessageRepository`'s own-message
`insert()` path and wherever synced messages land in `MessageDao` — by running
`MentionParser.findAll(message.body.text)` and inserting one row per unique mentioned author.
Never scanned for at query time (`LIKE '%@[...]%'` against `messages.text` would be slow and
fragile against 320-char free text); the table exists purely so "who mentioned me" is an indexed
`JOIN`, the same shape as every other membership query in this schema.

This needs a migration (new table, no column changes elsewhere) — whichever of this or the
QoL v8 batch (`NEXT.md`) lands first takes v8; the other becomes v9. Not resolved here since
build order isn't decided yet.

## Activity integration

Today, `MessageDao.pagedActivity`/`observeActivityUnreadCount` (`DriftwoodDatabase.kt`) match
only `par.author = :myAuthor` — "the immediate parent was a message I wrote." Mentions need a
second, independent match path: `EXISTS(SELECT 1 FROM mentions me WHERE me.message_id = r.id AND
me.author = :myAuthor)`, `OR`'d alongside the existing parent-chain condition (or written as a
`UNION`/adjusted `JOIN` — an implementation detail to settle when this is actually built, not
here). `ActivityRow`/`ActivityItem` gains a way to distinguish the two ("replied to you" vs
"mentioned you") for the row's copy.

**Overlap**: a message can both reply to you *and* mention you. Recommended default: dedupe to
one Activity row per message, "replied to you" wins the label when both are true (that's the
existing, already-understood semantics; "mentioned you" only applies when the message isn't
already a reply to your content). Open to revisiting if it reads oddly once built.

The existing blocklist/blocked-roots exclusion (`NOT EXISTS ... blocklist`, `... blocked_roots`)
applies identically to the mention path — a blocked author's mention of you should never surface,
same as their replies don't.

## Deep-linking to the mentioning thread — already works, no new mechanism

Verified against current code: `ThreadScreen`/`ThreadViewModel.observeThread` load a thread by
`rootId` alone (`DriftwoodDatabase.kt` `observeThread(threadRoot)`) with **no tier filtering**
anywhere in that path. Tier (`FOLLOW`/`CONTEXT`/`OTHER`) only gates which threads appear in the
Feed/Discover *list* queries (`pagedThreads`) — it has never gated opening a specific thread once
you have its id. Activity already proves this today: a reply from a total stranger (gossip-tier)
to your own message shows up in Activity and deep-links into `ThreadScreen` exactly like any
other entry, via the existing `Thread(rootId, focusMessageId)` nav key and
`onOpenActivityThread` callback.

So a "mentioned you" Activity row deep-links the identical way a "replied to you" row does —
same nav key, same callback, no new navigation capability to build. The only real case to
verify once implemented: a mention in a thread whose root was never held locally at all renders
the existing "the start of this thread isn't carried here" fallback (`ThreadScreen.kt`), same as
any other detached thread — not a mentions-specific gap.

## Rendering

Extends `ui/common/LinkifiedText.kt`'s existing span architecture rather than inventing a new
one — it already does exactly this shape of work for URLs: a second `remember`'d parse pass
(this time via `MentionParser.findAll`) producing its own span list alongside the URL one, each
mention range wrapped in `withLink`/a custom annotation styled distinctly (e.g. accent color +
weight, not underline, so it doesn't read as a hyperlink), and the existing `pointerInput`
long-press-gesture code (which already checks the URL span list at the tapped character offset)
extended to also check the mention span list.

- **Resolvable** (a name is known via `NameResolver`/`DirectoryRepository.observeNames()`, same
  resolution every other author reference in the app uses): render as the resolved display name,
  styled as a mention (not swapped for a full `AuthorName` chip — that's a heavier visual unit
  than inline text warrants, but open to revisiting once it's actually on screen). Tap opens
  `Contact` (or Settings, if it's you) — same pattern as tapping an author name elsewhere.
- **Not resolvable** (no name known for that identity): per your call, leave the original
  `@[authorHex]` text as-is, styling only — no truncation, no fingerprint substitution. Simplest
  rule, and consistent with "we show what we actually know, never less."
- Long-press menu on a mention (reusing the existing `DropdownMenu` machinery): "View profile" /
  "Copy id" in place of URL's "Copy link"/"Open link".

## Composing: `@` autocomplete

In scope for v1, not deferred — typing the raw 64-hex-char id by hand isn't a real authoring
experience. Same shape of work `FeedSearchField` already does (`ui/feed/FeedScreen.kt`) for its
author search box, reused rather than reinvented.

**Trigger and token boundary.** Typing `@` starts a candidate mention if it's preceded by
start-of-text or whitespace (so an email-shaped `foo@bar` never triggers one). The filter query
is everything typed after that `@` up to the cursor; it ends the moment whitespace, a newline, or
the picker's own insertion closes it. `@Ste` narrows toward "Steven" as more characters land,
exactly as you described.

**Candidates and ranking.** Source data is exactly what's already loaded for other
name-resolution in the app — `DirectoryRepository.observeNames()` (`Map<AuthorId, DisplayName>`,
already blending nickname and claimed username into one label — see `RoomDirectoryRepository
.observeNames()`) and `observeFollowList()` (`Set<AuthorId>`), the same two flows `FeedViewModel`
already combines for its own search. Filtering reuses `FeedSearchField`'s existing substring
match against `DisplayName.label` (matches nickname *or* claimed username, since a resolved
`DisplayName` is already whichever of those is set — no extra lookup needed to cover both).
Ranking: followed authors first, then everyone else; within each group, a label that *starts
with* the typed query beats one that merely *contains* it, then alphabetical — the same
`compareBy` tri-level sort `FeedSearchField` uses today, with a followed/not-followed partition
added in front of it. Blocked authors are excluded from suggestions (you can still hand-type a
blocked author's hex if you really mean to — this is just the picker not surfacing them).

**UI.** An `ExposedDropdownMenuBox`/`ExposedDropdownMenu` anchored under the compose field,
directly following `FeedSearchField`'s existing pattern — each row shows the candidate's
`DisplayName` (verified badge etc. exactly as `AuthorName` already renders it elsewhere).

**Insertion mechanics — the one real plumbing change.** `ComposeContent`'s text field currently
binds a plain `String` (`ComposeUiState.text` / `ComposeViewModel.updateText(text: String)`,
`ComposeScreen.kt`), which is enough for character-count/send logic but exposes no cursor
position — picking a candidate needs to know exactly where the `@query` span is to replace it
with `@[authorHex]`. That needs the `TextFieldValue` overload of `OutlinedTextField` (which
carries `selection: TextRange`) at least locally in `ComposeContent`; the domain layer
(`ComposeUiState`/`ComposeViewModel`) can stay plain-`String`-based as it is today; only the
Composable needs to track cursor position to know where to splice the replacement in and where
to move the cursor afterward (just past the closing `]`).

**Displaying the mention while still typing.** Once inserted, the draft's underlying text
contains the same raw `@[authorHex]` that gets sent — showing that raw string in the compose box
while you keep writing would be exactly the noisy-inline-hex problem flagged in Rendering above,
just earlier. Cleanest fix: a `VisualTransformation` on the compose `TextField` that swaps each
recognized `@[authorHex]` token for the resolved display name at render time only (Compose's
`VisualTransformation` exists precisely for "underlying value differs from displayed value" —
same idea as `LinkifiedText`'s render-time substitution, applied to the input side instead of the
message-display side), with an `OffsetMapping` translating cursor positions between the raw and
displayed strings. Follows the same resolvable/unresolvable rule as Rendering for consistency:
known name if resolvable, raw text otherwise.

**Interaction with Send — remove IME-send entirely, not just work around it.** The field
currently posts immediately on Enter/IME-send (`keyboardOptions = KeyboardOptions(imeAction =
ImeAction.Send)`, `keyboardActions = KeyboardActions(onSend = { if (state.canSend) onSend() })`,
`ComposeScreen.kt`). Piggybacking mention-confirm on that key was the wrong fix — a message has
no edit path once sent (only reply, or local deletion while still unsent), so Enter-to-send was
already a real footgun independent of mentions, and folding "confirm suggestion" onto the same
key would only have papered over it for the one case where a picker happens to be open. Bundling
into this work: drop `ImeAction.Send`/`onSend` altogether (Enter becomes an ordinary newline in
the multiline field, matching `minLines = 5`'s implied multiline intent anyway), and sending is
button-only (`Button(onClick = onSend, ...)`, already present, currently redundant with IME-send
and becomes the only path). That also fully resolves the mention-picker conflict rather than
requiring disambiguation logic: Enter unambiguously means "confirm the open suggestion" when the
picker is showing, and "newline" otherwise — it never means "send," so there's nothing left to
arbitrate.

## Setting: filtering whose mentions surface in Activity

In scope for v1, not deferred to "if it becomes a problem" — unlike general spam defense (see
Non-goals below), this is a real and *cheaper* exposure than replies already are. A reply
requires structurally replying to something you actually wrote; a mention can be dropped into
*any* message, in *any* thread, by anyone, whether or not you have any connection to that thread
at all. That's a lower-friction way for a stranger to insert themselves into your Activity list
than anything that exists today, so a simple filter belongs in v1 even though heavier defenses
(rate limiting, below) don't.

Same posture as local hiding: a pure display-layer filter, never touching what's held, relayed,
or synced — consistent with "Explicitly no sync/tiering effect" above. A Settings toggle (new
"Mentions" section, alongside "Hidden" in `SettingsScreen.kt`) — something like "Only show
mentions from people I follow." Implementation folds into the same Activity `OR`/`UNION` change
mentions already need: the mention branch additionally requires `me.author IN (SELECT author
FROM listen_list)` when the setting is on.

**Default — a real call, not obvious either way.** Showing everyone's mentions by default
matches how reply-to-you already behaves (zero follow-gating today), which argues for defaulting
permissive; the lower-friction insertion risk above argues for defaulting restrictive. I'd lean
toward defaulting to "show all," since a restrictive default silently drops content, which is a
worse failure mode than a permissive one you can tighten after the fact — but this is worth your
explicit call rather than an inherited default.

## First-use disclaimer

The first time someone opens the compose `@` picker (not the first time they *see* a mention —
the risk is in the wrong mental model at the moment of *writing* one), show a one-time dismissible
note: "Mentions aren't notifications. They only reach someone once a thread mentioning them syncs
to their device." Uses the `feature-first-use-tips.md` mechanism (tip id
`"mention-not-a-notification"`) rather than a one-off ad hoc flag — that doc has the full
rationale and confirms there's no existing "shown once" infrastructure to reuse, so building the
tip mechanism is itself part of shipping this disclaimer, not a prerequisite someone else needs
to build first.

## Non-goals for v1

- **No mention-based fair-share or tier effect**, per the scope decision above.
- **No author-level rate limiting or broader mention spam defense** beyond the follow-only filter
  above. Out of scope until shown to be a real problem — same posture as other abuse-shaped
  questions deferred elsewhere in this repo.

## Open questions

- Exact SQL shape for the Activity `OR`/`UNION` (see above) — an implementation detail, not
  blocking design.
- Whether an unresolvable mention's raw `@[64-hex-chars]` is too visually noisy inline once it's
  actually on screen (your call was explicit — leave it as text + styling — but worth a real
  look once built, since 67 raw characters in a 320-character message is a lot of a short post).
- Migration version coordination with the QoL v8 batch (`NEXT.md`) — resolved at build time by
  whichever lands first.
- Default value for "only show mentions from people I follow" (see setting section above) — flagged
  as a real product-posture decision, not resolved here.
- Whether the guided tutorial (README #8) gets rescoped around `feature-first-use-tips.md`'s
  mechanism — doesn't block this feature either way (see that doc).
