package com.safeguard.parentalcontrol.data.local

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.safeguard.parentalcontrol.data.model.AlertCreate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The on-device holding area for alerts awaiting delivery.
 *
 * Bounded, ordered, and app-private. Rows leave only when the backend has confirmed
 * acceptance, when the cap evicts the oldest, or when the account signs out.
 *
 * The payload is stored as the already-serialized alert JSON and replayed verbatim, so a
 * queued alert reaches the backend byte-identical to the one that would have been sent live.
 * Round-tripping through a typed model would coerce the untyped `metadata` values (an Int
 * would come back a Double and change the wire payload).
 */
@Singleton
class PendingAlertStore @Inject constructor(
    private val dao: PendingAlertDao,
    private val gson: Gson
) {
    companion object {
        /**
         * Maximum alerts held locally. Existing cooldown and daily-cap rules keep realistic
         * offline bursts far below this; the cap exists so a pathological burst cannot grow
         * the store without bound on a child's device.
         */
        const val MAX_PENDING = 100

        /** How many rows one drain pass pulls at a time. */
        const val DRAIN_BATCH = 20
    }

    /**
     * Hold an alert for later delivery, evicting the oldest entries if the queue is full.
     */
    suspend fun enqueue(payload: AlertCreate, deviceToken: String) = withContext(Dispatchers.IO) {
        val entity = PendingAlertEntity(
            clientAlertUuid = UUID.randomUUID().toString(),
            payloadJson = gson.toJson(payload),
            deviceToken = deviceToken,
            createdAt = System.currentTimeMillis()
        )
        dao.insertWithCap(entity, MAX_PENDING)
        Timber.d("Alert held for later delivery (queue size ${dao.count()})")
    }

    /** Oldest first — the order the violations actually happened in. */
    suspend fun oldestFirst(limit: Int = DRAIN_BATCH): List<PendingAlertEntity> =
        withContext(Dispatchers.IO) { dao.oldestFirst(limit) }

    /** Called only after the backend has confirmed acceptance. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { dao.deleteById(id) }

    suspend fun recordAttempt(id: Long) = withContext(Dispatchers.IO) {
        dao.recordAttempt(id, System.currentTimeMillis())
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    /**
     * Drop everything. Called on sign-out so held alerts are never delivered under a
     * different account.
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        val dropped = dao.count()
        dao.clear()
        if (dropped > 0) {
            Timber.d("Cleared $dropped held alert(s) on sign-out")
        }
    }

    /**
     * Parse a stored payload back into the raw JSON object to submit.
     *
     * @return null when the row is unreadable (corrupt write, schema drift). The caller drops
     *   such a row rather than retrying it forever.
     */
    fun parsePayload(entity: PendingAlertEntity): JsonObject? = try {
        JsonParser.parseString(entity.payloadJson).asJsonObject
    } catch (e: Exception) {
        Timber.w(e, "Dropping unreadable pending alert ${entity.id}")
        null
    }
}
