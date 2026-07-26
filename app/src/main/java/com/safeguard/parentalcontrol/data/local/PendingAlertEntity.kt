package com.safeguard.parentalcontrol.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An alert that was created on this child device but not yet accepted by the backend.
 *
 * Holds exactly the payload that `POST /alerts` already transmits — classification metadata
 * only. No monitored text, no image bytes, no URLs beyond what the alert itself carried
 * (Constitution Principle I). The queue exists so a violation detected without usable network
 * is delivered when connectivity returns instead of being silently lost.
 */
@Entity(
    tableName = "pending_alerts",
    indices = [Index(value = ["created_at"])]
)
data class PendingAlertEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Client-generated identity for this submission attempt. Reserved for backend
     * coordination item 5 (idempotency): if the backend adopts a dedup id, this is the value
     * we send, so a resubmission after an ambiguous timeout cannot create a duplicate alert.
     * Generated at enqueue time; not transmitted until the contract is confirmed.
     */
    @ColumnInfo(name = "client_alert_uuid")
    val clientAlertUuid: String,

    /** The enriched [com.safeguard.parentalcontrol.data.model.AlertCreate], Gson-serialized. */
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,

    /**
     * The `X-Device-Token` in effect when the alert was produced, so a queued alert is always
     * submitted under the identity that created it rather than whatever is current at drain
     * time.
     */
    @ColumnInfo(name = "device_token")
    val deviceToken: String,

    /** Epoch millis of enqueue. Drives both delivery order and oldest-out cap eviction. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** Diagnostics only — the retry schedule itself is WorkManager's. */
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,

    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null
)
