package com.safeguard.parentalcontrol.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * Data access for the pending-alert queue.
 *
 * Only [PendingAlertStore] may call this; nothing else in the app touches the DAO directly.
 */
@Dao
interface PendingAlertDao {

    @Insert
    suspend fun insert(entity: PendingAlertEntity): Long

    /** Oldest first — queued alerts are delivered in the order they were created. */
    @Query("SELECT * FROM pending_alerts ORDER BY created_at ASC LIMIT :limit")
    suspend fun oldestFirst(limit: Int): List<PendingAlertEntity>

    @Query("DELETE FROM pending_alerts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM pending_alerts")
    suspend fun count(): Int

    /**
     * Drop the [count] oldest rows. Used to enforce the queue cap: an unbounded local store on
     * a child device is itself a risk, and the newest violations are the actionable ones.
     */
    @Query("DELETE FROM pending_alerts WHERE id IN (SELECT id FROM pending_alerts ORDER BY created_at ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("UPDATE pending_alerts SET attempt_count = attempt_count + 1, last_attempt_at = :now WHERE id = :id")
    suspend fun recordAttempt(id: Long, now: Long)

    @Query("DELETE FROM pending_alerts")
    suspend fun clear()

    /**
     * Insert and enforce the cap atomically, so concurrent producers (accessibility service,
     * media observer, image scan worker, VPN tamper path) cannot interleave into an
     * over-capacity or under-capacity queue.
     */
    @Transaction
    suspend fun insertWithCap(entity: PendingAlertEntity, cap: Int) {
        insert(entity)
        val overflow = count() - cap
        if (overflow > 0) {
            deleteOldest(overflow)
        }
    }
}
