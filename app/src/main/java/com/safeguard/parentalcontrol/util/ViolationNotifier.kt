package com.safeguard.parentalcontrol.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.SafeGuardApplication
import com.safeguard.parentalcontrol.presentation.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts parent-facing violation notifications from a data-only FCM push.
 *
 * Exists so [com.safeguard.parentalcontrol.service.SafeGuardFirebaseMessagingService] holds no
 * business logic (Constitution enforcement rule 7): the service routes, this decides.
 *
 * Responsibilities:
 * - Role guard — a violation is only ever displayed on a parent device.
 * - Localized presentation — violation type and time render in the app's active language.
 * - Stable identity — repeat delivery of one alert updates its notification instead of
 *   stacking duplicates.
 * - Deep link — tapping opens the existing Alerts screen for the originating device.
 *
 * Every field of the push is optional except the delivery itself: a missing or malformed
 * value drops its segment rather than rendering a placeholder, an epoch number, or a raw
 * internal type string.
 */
@Singleton
class ViolationNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) {

    /**
     * Handle an incoming `type="alert"` push.
     *
     * @param data the FCM data payload; all values arrive as strings.
     */
    fun handleViolationPush(data: Map<String, String>) {
        // Role guard FIRST — before any parsing, building, or work scheduling. Showing a
        // violation on the monitored child's own device defeats the product and teaches
        // evasion, so a non-parent device records the event and displays nothing.
        if (!preferencesManager.isParent) {
            Timber.d("Violation push received on a non-parent device; discarded without display")
            AlertPipe.w("VIOLATION DISCARDED role=${preferencesManager.userRole ?: "none"} (not parent); nothing displayed")
            return
        }

        val push = parse(data)
        AlertPipe.i(
            "VIOLATION PARSED alert_id=${push.alertId} alert_type=${push.alertType} " +
                "severity=${push.severity} device_db_id=${push.deviceDbId} " +
                "occurred_at=${push.occurredAt} has_child_name=${push.childName != null} " +
                "has_device_name=${push.deviceName != null}"
        )
        val title = push.title?.takeIf { it.isNotBlank() } ?: getString(R.string.violation_notification_title)
        val body = push.body?.takeIf { it.isNotBlank() } ?: buildBody(push)

        val notification = NotificationCompat.Builder(context, SafeGuardApplication.CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(body)
            // Long child/device names are truncated on the collapsed line by the platform;
            // expanding shows the full text.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(buildDeepLinkIntent(push))
            .setPriority(priorityFor(push.severity))
            .setAutoCancel(true)
            .build()

        val notificationId = notificationIdFor(push.alertId)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (notificationManager == null) {
            AlertPipe.w("NOTIFY FAILED NotificationManager unavailable alert_id=${push.alertId}")
            return
        }

        AlertPipe.i(
            "NOTIFY channel=${SafeGuardApplication.CHANNEL_ALERTS} notification_id=$notificationId " +
                "alert_id=${push.alertId} priority=${priorityFor(push.severity)} " +
                "notifications_enabled=${NotificationManagerCompat.from(context).areNotificationsEnabled()}"
        )
        notificationManager.notify(notificationId, notification)
    }

    // ==================== Parsing ====================

    private fun parse(data: Map<String, String>): ViolationPush = ViolationPush(
        alertId = data["alert_id"]?.takeIf { it.isNotBlank() },
        alertType = data["alert_type"]?.takeIf { it.isNotBlank() },
        severity = data["severity"]?.takeIf { it.isNotBlank() },
        childName = data["child_name"]?.takeIf { it.isNotBlank() },
        deviceName = data["device_name"]?.takeIf { it.isNotBlank() },
        deviceDbId = data["device_db_id"]?.toIntOrNull(),
        // A malformed or absent timestamp drops the time segment entirely rather than
        // rendering "1970" or a raw epoch number.
        occurredAt = data["occurred_at"]?.toLongOrNull()?.takeIf { it > 0L },
        title = data["title"],
        body = data["body"]
    )

    /**
     * Compose "{child} — {device} · {type} · {time}", dropping any missing segment together
     * with its separator so the parent never sees a dangling dash or a double middot.
     */
    private fun buildBody(push: ViolationPush): String {
        val identity = listOfNotNull(push.childName, push.deviceName)
            .joinToString(getString(R.string.violation_identity_separator))
        val details = listOfNotNull(
            identity.takeIf { it.isNotBlank() },
            localizedViolationType(push.alertType),
            push.occurredAt?.let { formatOccurredAt(it) }
        )
        return details.joinToString(getString(R.string.violation_segment_separator))
    }

    private fun localizedViolationType(alertType: String?): String =
        getString(violationTypeLabelRes(alertType))

    private fun formatOccurredAt(occurredAt: Long): String = formatOccurredAt(
        occurredAt = occurredAt,
        locale = Locale.forLanguageTag(LocaleHelper.localeTagFor(LocaleHelper.getLanguage(context))),
        now = System.currentTimeMillis()
    )

    companion object {
        /**
         * Map the backend's violation type to a label resource. An unrecognized type falls
         * back to a generic label — the raw internal identifier must never reach the parent,
         * which is exactly what happens when a new backend type meets an older app.
         */
        internal fun violationTypeLabelRes(alertType: String?): Int = when (alertType?.lowercase()) {
            AlertTypes.INAPPROPRIATE_TEXT -> R.string.violation_type_inappropriate_text
            AlertTypes.INAPPROPRIATE_IMAGE -> R.string.violation_type_inappropriate_image
            AlertTypes.CONTENT_BLOCK -> R.string.violation_type_content_block
            AlertTypes.SCREEN_TIME_LIMIT -> R.string.violation_type_screen_time_limit
            AlertTypes.APP_BLOCKED -> R.string.violation_type_app_blocked
            AlertTypes.DEVICE_ADMIN_DISABLED -> R.string.violation_type_device_admin_disabled
            else -> R.string.violation_type_unknown
        }

        /**
         * Short time alone for a violation that happened today; short date plus time
         * otherwise. Rendered for the app's active locale, so Arabic gets Arabic numerals and
         * ordering rather than a fixed pattern.
         */
        internal fun formatOccurredAt(occurredAt: Long, locale: Locale, now: Long): String {
            val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(occurredAt))
            if (isSameDay(occurredAt, now, locale)) return time

            val date = DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(occurredAt))
            return "$date $time"
        }

        private fun isSameDay(first: Long, second: Long, locale: Locale): Boolean {
            val a = Calendar.getInstance(locale).apply { timeInMillis = first }
            val b = Calendar.getInstance(locale).apply { timeInMillis = second }
            return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }
    }

    // ==================== Presentation ====================

    /**
     * Preserved verbatim from the previous inline implementation: severity drives urgency,
     * with critical alerts getting full alerting treatment (sound and vibration).
     */
    private fun priorityFor(severity: String?): Int = when (severity?.lowercase()) {
        AlertSeverity.CRITICAL -> NotificationCompat.PRIORITY_MAX
        AlertSeverity.HIGH -> NotificationCompat.PRIORITY_HIGH
        else -> NotificationCompat.PRIORITY_DEFAULT
    }

    /**
     * Derive a stable notification id from the alert id so a re-delivered push updates the
     * existing notification instead of stacking a duplicate. Falls back to the shared alert
     * id when the push carries no alert id (nothing to be stable about).
     */
    private fun notificationIdFor(alertId: String?): Int =
        alertId?.hashCode() ?: Constants.NOTIFICATION_ID_ALERT

    /**
     * Open the EXISTING Alerts route for the originating device, pointed at the alert that was
     * actually tapped when the push identified one. Uses CLEAR_TOP (not CLEAR_TASK) so a
     * running app navigates rather than restarting; MainActivity is declared singleTop, so it
     * receives this through onNewIntent.
     */
    private fun buildDeepLinkIntent(push: ViolationPush): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(Constants.EXTRA_NAV_TARGET, Constants.NAV_TARGET_ALERTS)
            push.deviceDbId?.let { putExtra(Constants.EXTRA_DEVICE_ID, it) }
            push.deviceName?.let { putExtra(Constants.EXTRA_DEVICE_NAME, it) }
            // Alert ids arrive as strings and may be absent or malformed; a bad one degrades to
            // the device-filtered list rather than a broken target.
            push.alertId?.toIntOrNull()?.let { putExtra(Constants.EXTRA_ALERT_ID, it) }
        }

        return PendingIntent.getActivity(
            context,
            // Per-alert request code so a second alert does not overwrite the first
            // notification's target while FLAG_UPDATE_CURRENT is in play.
            notificationIdFor(push.alertId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getString(resId: Int): String =
        LocaleHelper.localizedContext(context).getString(resId)
}

/**
 * Parsed form of a data-only violation push. Everything is nullable: the notification
 * degrades one segment at a time rather than failing or showing placeholders.
 */
data class ViolationPush(
    val alertId: String?,
    val alertType: String?,
    val severity: String?,
    val childName: String?,
    val deviceName: String?,
    val deviceDbId: Int?,
    val occurredAt: Long?,
    val title: String?,
    val body: String?
)
