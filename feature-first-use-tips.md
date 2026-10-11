# First-use contextual tips

A small, reusable mechanism: show a short one-time explainer exactly when someone first
encounters a mechanic that would surprise them coming from another app, then never show it
again. Motivated by mentions (`feature-mentions.md`) needing to correct a real, wrong assumption
— "an @mention is like Twitter/Discord and will reach or notify someone" — but scoped as its own
piece because the same shape of problem recurs: this app deliberately behaves unlike other apps
in several places (sync is proximity-only and on-demand, tiers gate what you see, local deletion
only covers unsent messages, hiding vs. blocking are different levers), and a single monolithic
"guided tutorial" (README #8) either has to anticipate all of that on day one or leaves most of
it unexplained. A per-feature, shown-once tip lets each surprise get explained exactly when it's
relevant, added incrementally as features ship.

## Storage: genuinely new, nothing to reuse

Checked before proposing this: no Jetpack DataStore dependency exists anywhere in the app today,
no `SharedPreferences` usage anywhere, and the closest thing to persisted "first-run" state
(`introMode` / `justFinishedFirstRun` in `Navigation.kt`) is transient in-memory nav state that
resets every process — not a persistence mechanism at all. `IdentityStore`'s `SeedStorage`
(`core/identity`) is a single encrypted secret record purpose-built for the seed + backed-up
flag; repurposing it would mean either encrypting non-secret UI state for no reason or
duplicating its file+cipher plumbing for something that doesn't need encryption. So this is
additive infrastructure — a new dependency — not wiring up something half-built.

Jetpack DataStore (Preferences) is the natural fit: exactly the "small set of typed flags" shape
it's designed for. One `stringSetPreferencesKey` covers every future tip:

```kotlin
interface TipStore {
    fun observeShown(id: String): Flow<Boolean>
    suspend fun markShown(id: String)
}
```

A small composable, e.g. `FirstUseTip(id: String, content: @Composable (dismiss: () -> Unit) ->
Unit)`, checks `observeShown(id)` and renders `content` only when that id hasn't been shown,
calling `markShown(id)` on dismiss.

## First consumer: mentions

The compose-time `@` autocomplete (`feature-mentions.md`) shows a small dismissible note the
first time the picker triggers — something like "Mentions aren't notifications. They only reach
someone once a thread mentioning them syncs to their device." — tip id
`"mention-not-a-notification"`.

## Relationship to the guided tutorial (README #8)

Worth re-scoping that item around this mechanism rather than building one big walkthrough:
sync-on-demand, tier meanings, and fingerprint verification each become their own small tip,
shown the first time their screen/action is actually encountered, rather than a single
onboarding flow someone can skip past without absorbing. Not decided here — this doc and
mentions' disclaimer stand on their own regardless of whether the tutorial gets rescoped.

## Non-goals for v1

- No tip authoring/CMS — copy is hardcoded per call site, same as every other string in this app.
- No snooze/"remind me later" — a tip shows once, ever, or not at all.

## Open questions

- Should dismissing be reversible (a "Reset tips" button in Settings, mirroring "Run pruning
  now")? Cheap to add later; not needed for the first consumer.
