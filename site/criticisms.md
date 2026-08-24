# Criticism: `undertow.html`

A critical pass over the undertow page's copy, looking specifically for AI writing slop, weak
reasoning, poor wording, and unclear passages. Organized by severity, not by page order. Line
numbers refer to `site/undertow.html` as it stands. No changes made to the page itself.

## Real bugs (fix these regardless of style taste)

1. **"Neither list ever travels" has no second list to refer to.** (§8, line 429)
   > "The blocklist is local and private... Neither list ever travels, because announcing it
   > would tell the blocked person they'd been blocked..."

   The paragraph only ever names *one* list — the blocklist. "Neither" requires two
   antecedents. This reads like a leftover from an earlier draft that also mentioned the
   **blocked-roots list** (plan.md §3.3/§4 — root ids whose author is blocked, tracked
   separately so replies into that thread keep getting dropped after the root itself is
   gone) and lost that sentence during editing without the pronoun being fixed. Either
   reintroduce the second list explicitly, or change "Neither list" to "It" / "The list."

2. **Unsourced, suspiciously precise numbers in the impersonation paragraph.** (§8, lines
   421–422)
   > "a short fingerprint can be ground to match in minutes and a colour in a few dozen
   > attempts."

   Neither figure traces back to `plan.md` or `sync-spec.md` — the source material's actual
   line is closer to "no short fingerprint resists a determined impersonator," with no
   numbers attached. "In minutes" and "a few dozen attempts" look like they were invented for
   color during writing, not derived from anything. Worth checking the actual math before
   keeping them:
   - The fingerprint is 8 hex chars (4+4, ~32 bits of the hash) shown to the user. Grinding a
     *specific* target match (not a birthday collision — the attacker has to match one
     particular person's already-shown fingerprint) needs on the order of 2^31 candidate
     keypairs on average. Ed25519 keygen is an EC scalar multiplication, not a cheap hash —
     realistic single-core rates are tens of thousands of keys/sec at best, which puts a
     genuine attempt at hours-to-days, not "minutes," unless the claim is quietly assuming
     GPU/cluster compute that the sentence never mentions.
   - The color-matching claim ("a few dozen attempts") is more defensible if the hue is a
     simple `hash(key) mod 360` and "looks the same" means landing within a human-distinguishable
     band (~10–15°) — that's roughly a few dozen tries, so this one is probably fine, but it's
     still stated with a confidence the page never earns by showing the math.

   Either show the reasoning, cite the real constraint (bit-length of the fingerprint,
   `plan.md` §9), or drop the specific numbers and keep the qualitative claim, which is true
   and doesn't need a stopwatch attached to it.

## AI-writing tics (repeated patterns, not one-off style notes)

3. **The "X, not a bug / not an oversight" construction is used three times** across the two
   pages, twice on this one:
   - §3, line 174: "an expected, normal end-state, not a bug"
   - §8, line 436: "a deliberate trade, not an oversight"
   - (and on `index.html`: "normal here, not a bug")

   One of these lands. Three is a tell — it's a stock AI rhetorical move (assert X, then
   negate the reader's assumed objection) reached for by default rather than because the
   sentence needed it. Pick one place for it, rephrase the other two plainly.

4. **Redundant restatement inside single sentences/paragraphs**, saying the same thing twice
   for emphasis rather than once for clarity:
   - Intro paragraph (lines 37–38): "the protocol **underneath** driftwood — what actually
     happens **below the surface**." Underneath and below-the-surface are the same claim
     twice in a row.
   - §1, line 48–49: "there is no handle registry and no global namespace, and a name is
     never what identifies someone, only what they're called" — three phrasings of "keys,
     not names, are identity" stacked in one sentence.

5. **Italics-as-emphasis, used six separate times** across a fairly short page (lines 47,
   143, 173, 220, 398–399, 415: *is*, *exactly as received*, *message*, *not*, *what*,
   *receiver's*). No single instance is wrong, but the density reads as a habit rather than a
   deliberate choice — a page that leans on italics this often stops signalling anything with
   them.

6. **"Notice what isn't here"** (§2, line 101) is a stock explainer-writing imperative —
   telling the reader what to notice, rather than just noticing it for them. "There's no
   recipient field" says the same thing without narrating the act of reading.

## Reasoning gaps / under-explained claims

7. **"Never a negotiation that converges"** (§6, line 249) — "converges" has a specific,
   loaded meaning in distributed systems (iterative approach to a fixed point / eventual
   consistency), and it's used here loosely to mean something closer to "goes back and
   forth." This phrase is actually a direct quote from `sync-spec.md` §1, so it's not
   something invented for the site — but a reader who knows the formal meaning of
   "converges" may read more into it than intended, and the page doesn't gloss it either way.

8. **The integer-overflow framing bug** (§7, lines 343–346) states that a naive check "lets
   `0xFFFFFFFF` arrive as `-1` and slip through" but never explains *why* an upper-bound-only
   check would miss it — the actual mechanism is that `-1 < MAX_FRAME_BYTES` is true under a
   signed comparison, so a check that only tests the upper bound (and assumes length can't be
   negative) passes it straight through. The page states the consequence without showing the
   mechanism, which is fine for a marketing page but slightly thin for a "spec."

9. **"Which is the right shape for a protocol built around brief, in-person encounters"**
   (§6, lines 328–329) asserts a conclusion about design correctness rather than arguing for
   it — it's restating the design philosophy as though restating it were the same as
   justifying it. The sentence immediately after ("if the bus arrives, what you already
   exchanged is yours") does the actual justification — this clause is decorative filler
   riding on that sentence's coattails.

## Clarity / audience gaps

10. **"If the bus arrives"** (§6, line 329) is a callback to a framing this page never
    establishes — it assumes the reader already has the "brief interruptible encounter"
    mental model from `index.html`. Anyone who lands on `undertow.html` directly (search,
    a shared link, `undertow.html` outliving `index.html` in someone's bookmarks) hits an
    idiom with no antecedent.

11. **The nickname/username table never states whether it's protocol-level or client-level.**
    (§1) `contacts` vs. `directory` storage, and the display-color convention, read like they
    could be either a wire-protocol requirement or just this app's local UX choice. This
    matters more than it used to: with the plan to extract `undertow` as a library other
    developers implement against (per the newly-added M5 step in `plan.md`), a third-party
    implementer reading this page needs to know what's actually part of the protocol contract
    versus what's this client's own convention. Right now nothing on the page draws that
    line, here or anywhere else.

12. **"The network carries a moving present, not an archive"** (§5, line 222) is reused
    **verbatim** as a pull-quote on `index.html`. Fine as a tagline once; showing up
    unchanged on the page a reader clicks through to for *more* depth adds nothing new at the
    exact moment they're looking for it.

## One structural note, given where this is heading

The page presents itself with spec-like authority — numbered sections, monospace field
tables, byte-layout diagrams — but it's still an explainer, not an implementable spec: byte
order/encoding is only stated for the `length` field ("u32, big-endian"); nothing says how
`timestamp`, the listen set, wants, or `text`'s length prefix are actually encoded on the
wire. That's a reasonable scope for a web page today. It stops being reasonable the moment
`undertow` actually ships as a library for other people to implement against — at that point
this page will need either a real link out to a byte-exact spec, or an explicit "this page is
an overview, not the spec" disclaimer, so nobody starts an independent implementation from
what's here alone.
