package com.safeguard.parentalcontrol.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * App-private database. Currently holds only the pending-alert delivery queue.
 *
 * `exportSchema = false`: there is a single version and no migration path yet. Adding a
 * version 2 must either supply a migration or keep the destructive fallback deliberate —
 * losing queued alerts on upgrade would reintroduce the exact failure this feature fixes.
 */
@Database(
    entities = [PendingAlertEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SafeGuardDatabase : RoomDatabase() {
    abstract fun pendingAlertDao(): PendingAlertDao

    companion object {
        const val NAME = "safeguard.db"
    }
}
