package com.safeguard.parentalcontrol.util

import android.app.NotificationManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * A violation must never be displayed on the device it was detected on.
 *
 * Showing a child their own violation notification defeats the point of the product and
 * teaches evasion, so the guard runs before any parsing or notification building — which is
 * what makes it observable here without an Android runtime.
 */
class ViolationNotifierRoleGuardTest {

    private val notificationManager = mockk<NotificationManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(NotificationManager::class.java) } returns notificationManager
    }
    private val preferencesManager = mockk<PreferencesManager>(relaxed = true)

    private val notifier = ViolationNotifier(context, preferencesManager)

    private val violationPush = mapOf(
        "type" to "alert",
        "alert_id" to "9182",
        "alert_type" to AlertTypes.INAPPROPRIATE_TEXT,
        "severity" to "critical",
        "child_name" to "Ali",
        "device_name" to "Ali's Pixel",
        "occurred_at" to "1785000000000",
        "title" to "Violation detected",
        "body" to "Inappropriate text"
    )

    @Test
    fun `a child device shows nothing`() {
        every { preferencesManager.isParent } returns false

        notifier.handleViolationPush(violationPush)

        // Nothing posted, and nothing built either — no sound, no vibration, no trace.
        verify(exactly = 0) { notificationManager.notify(any(), any()) }
    }

    @Test
    fun `a child device is not spared by a critical severity`() {
        every { preferencesManager.isParent } returns false

        notifier.handleViolationPush(violationPush + ("severity" to "critical"))

        verify(exactly = 0) { notificationManager.notify(any(), any()) }
    }

    @Test
    fun `a device with no signed-in role shows nothing`() {
        // A signed-out device has no role, so it is not a parent and must stay silent.
        every { preferencesManager.isParent } returns false

        notifier.handleViolationPush(emptyMap())

        verify(exactly = 0) { notificationManager.notify(any(), any()) }
    }
}
