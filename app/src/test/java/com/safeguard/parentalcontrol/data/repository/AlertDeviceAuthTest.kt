package com.safeguard.parentalcontrol.data.repository

import android.content.Context
import com.safeguard.parentalcontrol.data.local.PendingAlertStore
import com.safeguard.parentalcontrol.data.model.Alert
import com.safeguard.parentalcontrol.data.model.AlertCreate
import com.safeguard.parentalcontrol.data.model.AlertSeverity
import com.safeguard.parentalcontrol.data.model.AlertType
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.TextHasher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import retrofit2.Response
import java.util.Date

/**
 * How an alert proves which device raised it.
 *
 * `POST /alerts` authenticates the *device* by the `device_token` the backend issued at
 * registration, carried in `X-Device-Token` — not by the locally-derived `device_id` UUID the
 * device registers under. Sending the UUID there is rejected outright, so every alert a child
 * raised was lost before it reached the parent. These tests pin the distinction so the two
 * identifiers cannot be swapped back.
 */
class AlertDeviceAuthTest {

    private companion object {
        /** Shaped like a real backend token: opaque, no dashes. */
        const val BACKEND_DEVICE_TOKEN = "NCL8Y48OtmY6fdxn0n6C1Cj6wbMyIrxUNt5A0OwiUNcVFaxUNTf3y3WjDpaGS7aXzicxzri58RQq8lbeBG4xXw"

        /** The device's own UUID — what used to be sent, and what must never be sent again. */
        const val LOCAL_DEVICE_UUID = "15b38b49-5af1-3ccb-b6a9-b0e00d15eaeb"

        const val DEVICE_DB_ID = 53
    }

    private val preferencesManager = mockk<PreferencesManager>(relaxed = true)
    private val apiService = mockk<ApiService>(relaxed = true)
    private val pendingAlertStore = mockk<PendingAlertStore>(relaxed = true)
    private val deviceRepository = mockk<DeviceRepository>(relaxed = true)

    private val repository = AlertRepository(
        context = mockk<Context>(relaxed = true),
        apiService = apiService,
        preferencesManager = preferencesManager,
        textHasher = mockk<TextHasher>(relaxed = true),
        pendingAlertStore = pendingAlertStore,
        deviceRepository = deviceRepository
    )

    private fun registeredDevice(deviceToken: String?) {
        every { preferencesManager.deviceDbId } returns DEVICE_DB_ID
        every { preferencesManager.deviceId } returns LOCAL_DEVICE_UUID
        every { preferencesManager.deviceToken } returns deviceToken
        coEvery { deviceRepository.ensureDeviceToken() } returns deviceToken
    }

    private fun accepted() = Response.success(
        Alert(
            id = 7,
            userId = 16,
            deviceId = DEVICE_DB_ID,
            alertType = AlertType.INAPPROPRIATE_TEXT,
            severity = AlertSeverity.HIGH,
            title = "Inappropriate content detected",
            message = "Flagged text was found in a browser tab.",
            metadata = null,
            isRead = false,
            isDismissed = false,
            createdAt = Date()
        )
    )

    @Test
    fun `an alert authenticates with the backend-issued device token`() = runTest {
        registeredDevice(BACKEND_DEVICE_TOKEN)
        val header = slot<String>()
        coEvery { apiService.createAlert(any(), capture(header)) } returns accepted()

        val result = repository.createAlert(
            alertType = AlertType.INAPPROPRIATE_TEXT,
            severity = AlertSeverity.HIGH,
            title = "Inappropriate content detected",
            message = "Flagged text was found in a browser tab.",
            metadata = mapOf("package_name" to "com.android.chrome", "confidence" to 0.94)
        )

        assertEquals(BACKEND_DEVICE_TOKEN, header.captured)
        assertEquals(true, result.isSuccess)
    }

    @Test
    fun `the alert body carries the device row id the backend knows`() = runTest {
        registeredDevice(BACKEND_DEVICE_TOKEN)
        val body = slot<AlertCreate>()
        coEvery { apiService.createAlert(capture(body), any()) } returns accepted()

        repository.createAlert(
            alertType = AlertType.INAPPROPRIATE_TEXT,
            severity = AlertSeverity.HIGH,
            title = "Inappropriate content detected",
            message = "Flagged text was found in a browser tab.",
            metadata = mapOf("package_name" to "com.android.chrome")
        )

        // `device_id` in the body is the numeric record id, not the UUID that goes in the
        // registration request — the two use the same name on the wire for different things.
        assertEquals(DEVICE_DB_ID, body.captured.deviceId)
        assertEquals("com.android.chrome", body.captured.metadata?.get("package_name"))
    }

    @Test
    fun `an alert is not submitted at all when no device token can be obtained`() = runTest {
        registeredDevice(deviceToken = null)

        val result = repository.createAlert(
            alertType = AlertType.INAPPROPRIATE_TEXT,
            severity = AlertSeverity.HIGH,
            title = "Inappropriate content detected",
            message = "Flagged text was found in a browser tab."
        )

        assertFalse(result.isSuccess)
        // Neither sent nor held: without a credential the request could never be accepted,
        // and queuing it would retry a rejection forever.
        coVerify(exactly = 0) { apiService.createAlert(any(), any()) }
        coVerify(exactly = 0) { pendingAlertStore.enqueue(any(), any()) }
    }
}
