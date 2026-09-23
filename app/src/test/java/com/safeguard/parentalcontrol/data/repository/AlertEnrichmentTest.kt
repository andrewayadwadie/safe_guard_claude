package com.safeguard.parentalcontrol.data.repository

import android.content.Context
import com.safeguard.parentalcontrol.BuildConfig
import com.safeguard.parentalcontrol.data.local.PendingAlertStore
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.TextHasher
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two decisions that every alert depends on:
 *
 * - what identity/timing gets attached, and who wins when both the caller and the repository
 *   supply the same key;
 * - whether a failed submission is worth holding, or is a decision that must not be queued.
 */
class AlertEnrichmentTest {

    private val preferencesManager = mockk<PreferencesManager>(relaxed = true)

    private val repository = AlertRepository(
        context = mockk<Context>(relaxed = true),
        apiService = mockk<ApiService>(relaxed = true),
        preferencesManager = preferencesManager,
        textHasher = mockk<TextHasher>(relaxed = true),
        pendingAlertStore = mockk<PendingAlertStore>(relaxed = true),
        deviceRepository = mockk<DeviceRepository>(relaxed = true)
    )

    private fun withIdentity(childName: String? = "Ali", deviceName: String? = "Ali's Pixel") {
        every { preferencesManager.userFullName } returns childName
        every { preferencesManager.deviceName } returns deviceName
    }

    // ==================== Enrichment ====================

    @Test
    fun `every alert carries identity, timing and app version`() {
        withIdentity()

        val metadata = repository.enrich(callerMetadata = null, deviceDbId = 42)

        assertEquals("Ali", metadata["child_name"])
        assertEquals("Ali's Pixel", metadata["device_name"])
        assertEquals(42, metadata["device_db_id"])
        assertEquals(BuildConfig.VERSION_NAME, metadata["app_version"])
        assertTrue("occurred_at must be an absolute timestamp", (metadata["occurred_at"] as Long) > 0L)
    }

    @Test
    fun `an alert that supplies its own value keeps it`() {
        withIdentity()

        val metadata = repository.enrich(
            callerMetadata = mapOf("device_name" to "Explicit device", "occurred_at" to 999L),
            deviceDbId = 42
        )

        assertEquals("Explicit device", metadata["device_name"])
        assertEquals(999L, metadata["occurred_at"])
        // Keys the caller did not supply are still enriched.
        assertEquals("Ali", metadata["child_name"])
    }

    @Test
    fun `caller metadata survives enrichment`() {
        withIdentity()

        val metadata = repository.enrich(
            callerMetadata = mapOf("package_name" to "com.whatsapp", "confidence" to 0.81f),
            deviceDbId = 42
        )

        assertEquals("com.whatsapp", metadata["package_name"])
        assertEquals(0.81f, metadata["confidence"])
    }

    @Test
    fun `a missing child name is omitted rather than blank`() {
        withIdentity(childName = null)

        val metadata = repository.enrich(callerMetadata = null, deviceDbId = 42)

        assertFalse("An unknown name must not render an empty segment", metadata.containsKey("child_name"))
        assertEquals("Ali's Pixel", metadata["device_name"])
    }

    @Test
    fun `a blank stored name counts as missing`() {
        withIdentity(childName = "   ")

        assertFalse(repository.enrich(null, 42).containsKey("child_name"))
    }

    // ==================== Retry classification ====================

    @Test
    fun `a transport failure is held for retry`() {
        // safeApiCall surfaces network and IO failures with no HTTP status.
        assertTrue(repository.isRetryable(null))
    }

    @Test
    fun `server-side failures are held for retry`() {
        assertTrue(repository.isRetryable(500))
        assertTrue(repository.isRetryable(502))
        assertTrue(repository.isRetryable(503))
    }

    @Test
    fun `rejections are not held`() {
        // The backend will never accept these, so holding them would stall the queue.
        assertFalse(repository.isRetryable(400))
        assertFalse(repository.isRetryable(401))
        assertFalse(repository.isRetryable(403))
        assertFalse(repository.isRetryable(404))
        assertFalse(repository.isRetryable(422))
        assertFalse(repository.isRetryable(429))
    }

    @Test
    fun `an accepted submission is not held`() {
        assertFalse(repository.isRetryable(200))
        assertFalse(repository.isRetryable(201))
    }
}
