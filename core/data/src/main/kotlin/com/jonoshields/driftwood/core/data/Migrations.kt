package com.jonoshields.driftwood.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema version bump gets one `val MIGRATION_<from>_<to>` here, added to
 * [DRIFTWOOD_MIGRATIONS] below. This app has real users now, so a missing migration must fail
 * loudly (see [buildDriftwoodDatabase]) rather than silently wiping their message store.
 *
 * Migration SQL targets the actual on-disk schema — check the exported JSON at
 * `core/data/schemas/com.jonoshields.driftwood.core.data.DriftwoodDatabase/<N>.json` for the
 * version you're migrating from, don't assume it matches the current Kotlin entity. Kotlin-side
 * names are free to move independently of on-disk column/table names and stored values — see
 * `Converters.fromTier`/`toTier` in Entities.kt, which deliberately keeps `Tier.FOLLOW` stored as
 * the legacy string `"LISTEN"`. The same discipline applies here: renaming a Kotlin property is
 * not itself a migration; only an actual column/table change is.
 *
 * After adding a migration, re-run the Room schema-export Gradle task so
 * `schemas/.../<N+1>.json` is generated, then diff it against the entity as a sanity check.
 *
 * No `fallbackToDestructiveMigrationOnDowngrade()` either — a downgrade should also fail loudly,
 * not wipe. There is also no need for any migration below version 3: every real install has only
 * ever existed at v3 (this database's version at the time it first shipped), so nothing needs a
 * migration path from v1 or v2.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contacts ADD COLUMN verified INTEGER NOT NULL DEFAULT 0")
    }
}

internal val DRIFTWOOD_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_3_4)
