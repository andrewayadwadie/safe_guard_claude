package com.safeguard.parentalcontrol.util

import com.safeguard.parentalcontrol.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * How a violation is described to the parent: which label a violation type gets, and how its
 * time is written.
 *
 * Both are pure decisions, so they are exercised directly rather than through a notification.
 */
class ViolationNotifierFormatTest {

    // ==================== Violation type labels ====================

    @Test
    fun `each known violation type gets its own label`() {
        val labels = listOf(
            AlertTypes.INAPPROPRIATE_TEXT,
            AlertTypes.INAPPROPRIATE_IMAGE,
            AlertTypes.CONTENT_BLOCK,
            AlertTypes.SCREEN_TIME_LIMIT,
            AlertTypes.APP_BLOCKED,
            AlertTypes.DEVICE_ADMIN_DISABLED
        ).map { ViolationNotifier.violationTypeLabelRes(it) }

        assertEquals("Each type must map to a distinct label", labels.size, labels.toSet().size)
        assertTrue(
            "No known type may fall through to the generic label",
            labels.none { it == R.string.violation_type_unknown }
        )
    }

    @Test
    fun `an unrecognized type falls back to the generic label`() {
        // A backend that starts sending a type this app version does not know must never
        // surface the raw internal identifier to a parent.
        assertEquals(
            R.string.violation_type_unknown,
            ViolationNotifier.violationTypeLabelRes("some_future_type")
        )
    }

    @Test
    fun `a missing type falls back to the generic label`() {
        assertEquals(
            R.string.violation_type_unknown,
            ViolationNotifier.violationTypeLabelRes(null)
        )
    }

    @Test
    fun `type matching ignores case`() {
        assertEquals(
            ViolationNotifier.violationTypeLabelRes(AlertTypes.INAPPROPRIATE_TEXT),
            ViolationNotifier.violationTypeLabelRes("INAPPROPRIATE_TEXT")
        )
    }

    // ==================== Time formatting ====================

    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.JULY, 25, 14, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun hoursAgo(hours: Int) = now - hours * 60L * 60L * 1000L

    @Test
    fun `a violation from today shows only the time`() {
        val today = ViolationNotifier.formatOccurredAt(hoursAgo(2), Locale.ENGLISH, now)
        val yesterday = ViolationNotifier.formatOccurredAt(hoursAgo(26), Locale.ENGLISH, now)

        assertTrue("Today's format must be shorter than an earlier day's", today.length < yesterday.length)
        assertNotEquals(today, yesterday)
    }

    @Test
    fun `a violation from an earlier day includes the date`() {
        val earlier = ViolationNotifier.formatOccurredAt(hoursAgo(26), Locale.ENGLISH, now)
        val timeOnly = ViolationNotifier.formatOccurredAt(hoursAgo(2), Locale.ENGLISH, now)

        assertTrue("An earlier day must carry a date segment", earlier.contains(" "))
        assertTrue(earlier.length > timeOnly.length)
    }

    @Test
    fun `late last night is treated as an earlier day, not as hours ago`() {
        // 14 hours before 14-30 is 00-30 the same morning; 15 hours is 23-30 yesterday.
        val thisMorning = ViolationNotifier.formatOccurredAt(hoursAgo(14), Locale.ENGLISH, now)
        val lastNight = ViolationNotifier.formatOccurredAt(hoursAgo(15), Locale.ENGLISH, now)

        assertTrue(lastNight.length > thisMorning.length)
    }

    @Test
    fun `formatting follows the requested locale`() {
        val english = ViolationNotifier.formatOccurredAt(hoursAgo(26), Locale.ENGLISH, now)
        val arabic = ViolationNotifier.formatOccurredAt(hoursAgo(26), Locale("ar"), now)

        // The parent's language decides the rendering, not the device's system locale.
        assertNotEquals(english, arabic)
    }
}
