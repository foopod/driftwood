package com.jonoshields.driftwood.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the migration test harness itself works — schema asset resolution, [MigrationTestHelper]
 * wiring, and query-after-open — ahead of there being a real migration to exercise. When
 * `MIGRATION_N_(N+1)` is added to Migrations.kt, extend this file following the same
 * open -> seed -> migrate -> assert shape used by [smokeTestOpensExportedV3SchemaAndKeepsData].
 */
@RunWith(AndroidJUnit4::class)
class DriftwoodMigrationTest {

    private val testDbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DriftwoodDatabase::class.java,
    )

    @Test
    fun smokeTestOpensExportedV3SchemaAndKeepsData() {
        helper.createDatabase(testDbName, 3).apply {
            execSQL(
                "INSERT INTO contacts (author, display_name, added_at) VALUES (?, ?, ?)",
                arrayOf<Any>(ByteArray(32) { 1 }, "Jono", 1000L),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 3, true)
        val cursor = migrated.query("SELECT display_name FROM contacts")
        cursor.use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("Jono", it.getString(0))
        }
    }

    @Test
    fun migration3To4AddsVerifiedColumnWithoutLosingExistingRows() {
        helper.createDatabase(testDbName, 3).apply {
            execSQL(
                "INSERT INTO contacts (author, display_name, added_at) VALUES (?, ?, ?)",
                arrayOf<Any>(ByteArray(32) { 1 }, "Jono", 1000L),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 4, true, MIGRATION_3_4)
        val cursor = migrated.query("SELECT display_name, verified FROM contacts")
        cursor.use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("Jono", it.getString(0))
            assertEquals(0, it.getInt(1))
        }
    }

    @Test
    fun migration4To5AddsUnsentColumnWithoutLosingExistingRows() {
        helper.createDatabase(testDbName, 4).apply {
            execSQL(
                "INSERT INTO messages (id, version, author, root, parent, thread_root, " +
                    "timestamp_millis, text, signature, first_received_time, effective_time, read, tier) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(
                    ByteArray(32) { 1 }, 1, ByteArray(32) { 2 }, null, null, ByteArray(32) { 1 },
                    1000L, "hello", ByteArray(64) { 3 }, 1000L, 1000L, 1, "GOSSIP",
                ),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 5, true, MIGRATION_4_5)
        val cursor = migrated.query("SELECT text, unsent FROM messages")
        cursor.use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("hello", it.getString(0))
            // A message already on disk before this column existed is never assumed unsent.
            assertEquals(0, it.getInt(1))
        }
    }
}
