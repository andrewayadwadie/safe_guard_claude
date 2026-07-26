package com.safeguard.parentalcontrol.data.local

import com.google.gson.Gson
import com.safeguard.parentalcontrol.data.model.AlertCreate
import com.safeguard.parentalcontrol.data.model.AlertSeverity
import com.safeguard.parentalcontrol.data.model.AlertType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behaviour of the pending-alert queue: bounded, ordered, and lossless.
 *
 * Uses a hand-written in-memory [PendingAlertDao] rather than an in-memory Room database so
 * these stay plain JVM unit tests. The cap logic under test lives in the DAO's default
 * `insertWithCap`, which is exercised through the real implementation here.
 */
class PendingAlertStoreTest {

    private lateinit var dao: FakePendingAlertDao
    private lateinit var store: PendingAlertStore

    @Before
    fun setUp() {
        dao = FakePendingAlertDao()
        store = PendingAlertStore(dao, Gson())
    }

    private fun alert(title: String) = AlertCreate(
        deviceId = 42,
        alertType = AlertType.INAPPROPRIATE_TEXT,
        severity = AlertSeverity.HIGH,
        title = title,
        message = "message",
        metadata = mapOf("device_db_id" to 42, "occurred_at" to 1_700_000_000_000L),
        evidenceData = null
    )

    @Test
    fun `queue never grows past the cap`() = runTest {
        repeat(PendingAlertStore.MAX_PENDING + 50) { index ->
            dao.nextCreatedAt = index.toLong()
            store.enqueue(alert("alert-$index"), "device-token")
        }

        assertEquals(PendingAlertStore.MAX_PENDING, store.count())
    }

    @Test
    fun `overflow drops the oldest and keeps the newest`() = runTest {
        val total = PendingAlertStore.MAX_PENDING + 5
        repeat(total) { index ->
            dao.nextCreatedAt = index.toLong()
            store.enqueue(alert("alert-$index"), "device-token")
        }

        val held = store.oldestFirst(limit = PendingAlertStore.MAX_PENDING)

        // The five earliest were evicted; the most recent survives.
        assertEquals(5L, held.first().createdAt)
        assertEquals((total - 1).toLong(), held.last().createdAt)
        assertTrue(held.none { it.createdAt < 5L })
    }

    @Test
    fun `entries come back oldest first regardless of insertion order`() = runTest {
        dao.nextCreatedAt = 300L
        store.enqueue(alert("third"), "t")
        dao.nextCreatedAt = 100L
        store.enqueue(alert("first"), "t")
        dao.nextCreatedAt = 200L
        store.enqueue(alert("second"), "t")

        val order = store.oldestFirst().map { it.createdAt }

        assertEquals(listOf(100L, 200L, 300L), order)
    }

    @Test
    fun `sign-out clears everything`() = runTest {
        repeat(5) { store.enqueue(alert("alert-$it"), "device-token") }

        store.clear()

        assertEquals(0, store.count())
    }

    @Test
    fun `the stored payload replays unchanged`() = runTest {
        store.enqueue(alert("keep me exact"), "device-token")

        val payload = store.parsePayload(store.oldestFirst().single())

        assertNotNull(payload)
        assertEquals("keep me exact", payload!!["title"].asString)
        // Whole numbers must not drift to doubles on the way back out, or the queued alert
        // would reach the backend with a different payload than a live one.
        assertEquals("42", payload["metadata"].asJsonObject["device_db_id"].asString)
    }

    @Test
    fun `an unreadable payload is reported rather than thrown`() {
        val corrupt = PendingAlertEntity(
            id = 1,
            clientAlertUuid = "uuid",
            payloadJson = "{ not json",
            deviceToken = "t",
            createdAt = 1L
        )

        assertNull(store.parsePayload(corrupt))
    }

    @Test
    fun `the device token in effect at enqueue is preserved`() = runTest {
        store.enqueue(alert("a"), "token-at-detection-time")

        assertEquals("token-at-detection-time", store.oldestFirst().single().deviceToken)
    }

    /**
     * Minimal in-memory stand-in. Overrides only the abstract queries; `insertWithCap` runs
     * the real interface default so the cap behaviour under test is production code.
     */
    private class FakePendingAlertDao : PendingAlertDao {
        private val rows = mutableListOf<PendingAlertEntity>()
        private var nextId = 1L

        /** Lets a test control ordering without sleeping on the wall clock. */
        var nextCreatedAt: Long? = null

        override suspend fun insert(entity: PendingAlertEntity): Long {
            val id = nextId++
            rows += entity.copy(id = id, createdAt = nextCreatedAt ?: entity.createdAt)
            return id
        }

        override suspend fun oldestFirst(limit: Int): List<PendingAlertEntity> =
            rows.sortedBy { it.createdAt }.take(limit)

        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun count(): Int = rows.size

        override suspend fun deleteOldest(count: Int) {
            rows.sortedBy { it.createdAt }.take(count).forEach { row -> rows.remove(row) }
        }

        override suspend fun recordAttempt(id: Long, now: Long) {
            val index = rows.indexOfFirst { it.id == id }
            if (index >= 0) {
                rows[index] = rows[index].copy(
                    attemptCount = rows[index].attemptCount + 1,
                    lastAttemptAt = now
                )
            }
        }

        override suspend fun clear() = rows.clear()
    }
}
