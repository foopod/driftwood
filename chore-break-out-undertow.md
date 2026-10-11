# Chore: break undertow out into its own repo

Extract the protocol core (`core:model`, `core:crypto`, `core:store`, `core:sync`) from
`driftwood` into a standalone Kotlin library repo, `undertow`. These four modules are already
Android-free by design (README: "kept free of Android imports on purpose — the protocol and
data model are meant to be implementable by a third party without depending on this app") and
form a clean dependency chain with zero fan-out into the rest of the app:

```
core/sync    -> core/store, core/model
core/store   -> core/model
core/model   -> core/crypto
core/crypto  -> (bouncycastle only)
```

Do the rename, API-boundary, and licensing work *as part of* the move, not after — all three
get much more expensive once anything external depends on the new repo.

## What moves vs. what stays

**Moves:** `core/model`, `core/crypto`, `core/store`, `core/sync` (~6.4k lines incl. tests),
plus `site/undertow.html` (the protocol spec — it's documentation *for the library*, not for
the app, and should live with the code it describes).

**Stays in driftwood:** `core:identity` (Android Keystore-backed) and `core:data` (Room) —
these are driftwood's Android-specific *implementations* of storage/identity, not the protocol
itself. `:app` obviously stays.

## Steps

1. **Split history into the new repo.**
   Use `git filter-repo` (not a fresh copy) so `ConvergenceTest`, `SpecScenariosTest`, and the
   rest of the sync test suite keep their design history:
   ```
   git clone --no-local /home/foo/src/gossip /tmp/undertow-extract
   cd /tmp/undertow-extract
   git filter-repo \
     --path core/model --path core/crypto --path core/store --path core/sync \
     --path site/undertow.html
   ```
   Confirm the resulting log still shows the real commits that shaped the protocol (not just
   "Feed / Activity revamp"-era noise) before pushing this anywhere.

2. **Rename the package away from `driftwood`.**
   Everything is currently `com.jonoshields.driftwood.core.*`. Third-party implementability is
   the whole point, so rename to `com.jonoshields.undertow.*` (drop the `driftwood` segment,
   drop `core` since there's no longer a sibling non-core) in the same pass as the extraction —
   do this before anyone else has a dependency on the old names, not as a follow-up major
   version bump.

3. **New minimal build, no `build-logic` needed.**
   The only thing the four modules currently borrow from driftwood's `build-logic` is
   `driftwood.jvm.library`, which is just `kotlin.jvm` + a JVM toolchain + `useJUnit()`
   (`build-logic/convention/src/main/kotlin/JvmLibraryConventionPlugin.kt`). Inline that
   directly in the new repo's root `build.gradle.kts` — no composite build-logic project
   needed for something this small. Root `settings.gradle.kts` becomes:
   ```
   rootProject.name = "undertow"
   include(":model", ":crypto", ":store", ":sync")
   ```
   Bring the relevant slice of `gradle/libs.versions.toml` (kotlin `2.3.20`, bouncycastle
   `1.83`, kotlinx-coroutines-core, junit) rather than the whole catalog.

4. **Enforce a public API boundary.**
   Nothing currently stops internal reconciliation/session-state helpers from being
   accidentally public. Add `kotlin { explicitApi() }` to each module as part of the move, fix
   the fallout, and treat that fixed-up surface (`Session`, `Framing`, `Reconciler`, the
   `Transport`-shaped interfaces) as the actual contract going forward.

5. **Add a LICENSE.**
   There is currently no `LICENSE` file anywhere in `driftwood`. A library meant for
   third-party implementers can't respectably be depended on without one — pick one before the
   first tag.

6. **Decide library-version vs. wire-version now.**
   Two different numbers, don't conflate them:
   - **Library version** (semver on the Kotlin API) — bump on API changes to `Session`,
     `Reconciler`, etc.
   - **Wire/protocol version** (compat between two phones running different driftwood
     versions) — bump only on message-format/framing changes. This has to keep meaning
     something even as the library's Kotlin API evolves underneath it, so decide where it
     lives (a constant exposed from `core.sync`'s public API, most likely) before the first
     release rather than retrofitting it once two independent apps depend on this.

7. **CI for the new repo.**
   `./gradlew test` on four plain-JVM modules — no Android SDK, no emulator, so this can be a
   much simpler/faster workflow than driftwood's own CI.

8. **Wire driftwood back up against it.**
   - Day-to-day dev: `includeBuild("../undertow")` as a composite build in driftwood's
     `settings.gradle.kts`, so local edits to undertow are live without publishing anything.
   - Real releases: publish undertow to a low-friction target (GitHub Packages or JitPack —
     not Maven Central/Sonatype, too heavyweight for a solo project right now) and have
     driftwood depend on the published coordinate for release builds.
   - Delete `core/model`, `core/crypto`, `core/store`, `core/sync` from driftwood, update
     `settings.gradle.kts` (drop the four `include(...)` lines), and fix up dependents
     (`core:identity`, `core:data`, `:app`) to import `com.jonoshields.undertow.*` instead of
     `com.jonoshields.driftwood.core.*`.
   - Update `README.md`'s project-layout section and the `site/undertow.html` link (it now
     points at another repo, not `site/undertow.html` in-tree — decide whether driftwood's
     README links out to the new repo's copy or keeps a stale local copy in sync).

## Open questions to resolve before starting

- Repo name/location: `undertow` under the same GitHub account, presumably — confirm before
  step 1 since `git filter-repo` output needs a destination.
- Publishing target for step 8 (GitHub Packages vs. JitPack) — affects how `build.gradle.kts`
  is configured in step 3, so worth deciding early even though publishing itself is a later
  step.
- Whether `site/undertow.html` moves wholesale or driftwood keeps a synced copy for its own
  README link.
