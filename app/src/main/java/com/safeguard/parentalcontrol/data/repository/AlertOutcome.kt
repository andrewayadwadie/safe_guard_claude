package com.safeguard.parentalcontrol.data.repository

import com.safeguard.parentalcontrol.data.model.Alert

/**
 * Why an alert was intentionally not sent.
 *
 * Suppression is a decision, not a failure. Returning it as `NetworkResult.Error("Cooldown
 * active…")` made the two indistinguishable at the call site: a cooled-down alert and a server
 * outage produced the same type carrying the same shape of message, so neither the code nor
 * a QA log could tell "we chose not to send this" from "we tried and could not".
 *
 * Each case carries the numbers that explain the decision, so the reason can be logged in full
 * rather than restated as prose.
 */
sealed interface AlertSkipReason {

    /** Identical content in the same app inside the dedup window. */
    data class DuplicateContent(val windowMs: Long) : AlertSkipReason

    /** This category alerted too recently. [remainingMs] is the wait before the next one. */
    data class CooldownActive(val category: String, val remainingMs: Long) : AlertSkipReason

    /** This category hit its cap for the day. */
    data class DailyLimitReached(val category: String, val count: Int, val limit: Int) : AlertSkipReason

    /** No device record yet, so there is nothing to attribute the alert to. Never retried. */
    data object DeviceNotRegistered : AlertSkipReason

    /** Compact machine-readable form for [com.safeguard.parentalcontrol.util.AlertPipe] lines. */
    fun describe(): String = when (this) {
        is DuplicateContent -> "duplicate_content window_ms=$windowMs"
        is CooldownActive -> "cooldown category=$category remaining_ms=$remainingMs"
        is DailyLimitReached -> "daily_limit category=$category count=$count limit=$limit"
        DeviceNotRegistered -> "device_not_registered"
    }
}

/**
 * What became of one violation's alert.
 *
 * Three outcomes, deliberately distinct: the backend accepted it, we chose not to send it, or
 * we tried and it did not land. The third is the only one that says anything is wrong.
 */
sealed interface AlertOutcome {

    /** The backend accepted the alert. */
    data class Delivered(val alert: Alert) : AlertOutcome

    /** Suppressed on purpose. Nothing was sent and nothing was queued. */
    data class Skipped(val reason: AlertSkipReason) : AlertOutcome

    /**
     * Submission was attempted and did not succeed. [queuedForRetry] distinguishes a delivery
     * that is still coming (held by [AlertRepository.flushPendingAlerts]) from one the backend
     * refused outright.
     */
    data class Failed(
        val message: String,
        val code: Int?,
        val queuedForRetry: Boolean
    ) : AlertOutcome
}
