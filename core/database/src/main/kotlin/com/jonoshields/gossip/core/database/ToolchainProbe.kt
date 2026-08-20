package com.jonoshields.gossip.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * A deliberately throwaway schema whose only job is to prove that Room's KSP processor
 * generates and compiles against this exact AGP 9 / Kotlin 2.3 / KSP 2.3 combination.
 *
 * There is no real schema in M0 — the message store is designed in M1 (plan.md §8), where
 * this file is deleted and replaced. Proving the annotation-processor toolchain now, with
 * nothing invested, is much cheaper than discovering a version mismatch in M1 with a real
 * schema and its migrations riding on it.
 */
@Entity(tableName = "toolchain_probe")
internal data class ToolchainProbeEntity(
    @PrimaryKey val id: Long,
    val note: String,
)

@Dao
internal interface ToolchainProbeDao {
    @Insert
    suspend fun insert(entity: ToolchainProbeEntity)

    @Query("SELECT * FROM toolchain_probe WHERE id = :id")
    suspend fun findById(id: Long): ToolchainProbeEntity?
}

@Database(entities = [ToolchainProbeEntity::class], version = 1, exportSchema = true)
internal abstract class ProbeDatabase : RoomDatabase() {
    abstract fun probeDao(): ToolchainProbeDao
}
