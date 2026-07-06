package com.safeguard.parentalcontrol.util

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device-only store of flagged text phrases, kept solely so a parent can review
 * what was flagged on the child's phone.
 *
 * Privacy / Play-Store compliance: the actual phrase NEVER leaves the device. The
 * monitoring path still uploads only privacy-safe metadata (category, confidence) as an
 * alert — see AlertRepository.createInappropriateTextAlert. This local log is the only
 * place the raw phrase is retained, it lives in EncryptedSharedPreferences (encrypted at
 * rest), is gated behind the parent PIN, capped, and auto-purged after [RETENTION_MS].
 */
@Singleton
class FlaggedTextStore @Inject constructor(
    private val prefs: PreferencesManager
) {
    /** Record a newly flagged phrase. Newest first; prunes old/excess entries. */
    @Synchronized
    fun add(phrase: String, appName: String, category: String, timestamp: Long = System.currentTimeMillis()) {
        try {
            val entry = JSONObject().apply {
                put(FIELD_PHRASE, phrase.trim().take(MAX_PHRASE_LEN))
                put(FIELD_APP, appName)
                put(FIELD_CATEGORY, category)
                put(FIELD_TIMESTAMP, timestamp)
            }
            val events = loadArray()
            val pruned = prune(events, now = timestamp)
            // Prepend newest.
            val out = JSONArray().put(entry)
            for (i in 0 until pruned.length()) out.put(pruned.getJSONObject(i))
            // Cap total.
            val capped = if (out.length() > MAX_ENTRIES) {
                JSONArray().also { for (i in 0 until MAX_ENTRIES) it.put(out.getJSONObject(i)) }
            } else out
            prefs.putString(Constants.KEY_FLAGGED_TEXT_EVENTS, capped.toString())
        } catch (e: Exception) {
            Timber.e(e, "FlaggedTextStore: failed to add entry")
        }
    }

    /** All retained flagged phrases, newest first, with expired ones dropped. */
    @Synchronized
    fun getAll(): List<FlaggedTextEvent> {
        val pruned = prune(loadArray(), now = System.currentTimeMillis())
        // Persist the prune so expired entries don't linger on disk.
        prefs.putString(Constants.KEY_FLAGGED_TEXT_EVENTS, pruned.toString())
        return buildList {
            for (i in 0 until pruned.length()) {
                val o = pruned.getJSONObject(i)
                add(
                    FlaggedTextEvent(
                        phrase = o.optString(FIELD_PHRASE),
                        appName = o.optString(FIELD_APP),
                        category = o.optString(FIELD_CATEGORY),
                        timestamp = o.optLong(FIELD_TIMESTAMP)
                    )
                )
            }
        }
    }

    /** Delete a single entry by its timestamp (used as the row id). */
    @Synchronized
    fun delete(timestamp: Long) {
        val events = loadArray()
        val out = JSONArray()
        for (i in 0 until events.length()) {
            val o = events.getJSONObject(i)
            if (o.optLong(FIELD_TIMESTAMP) != timestamp) out.put(o)
        }
        prefs.putString(Constants.KEY_FLAGGED_TEXT_EVENTS, out.toString())
    }

    /** Remove every retained phrase. */
    @Synchronized
    fun clear() {
        prefs.remove(Constants.KEY_FLAGGED_TEXT_EVENTS)
    }

    private fun loadArray(): JSONArray {
        val raw = prefs.getString(Constants.KEY_FLAGGED_TEXT_EVENTS, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun prune(events: JSONArray, now: Long): JSONArray {
        val cutoff = now - RETENTION_MS
        val out = JSONArray()
        for (i in 0 until events.length()) {
            val o = events.optJSONObject(i) ?: continue
            if (o.optLong(FIELD_TIMESTAMP) >= cutoff) out.put(o)
        }
        return out
    }

    companion object {
        private const val MAX_PHRASE_LEN = 300
        private const val MAX_ENTRIES = 200
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

        private const val FIELD_PHRASE = "phrase"
        private const val FIELD_APP = "appName"
        private const val FIELD_CATEGORY = "category"
        private const val FIELD_TIMESTAMP = "timestamp"
    }
}

/** A single flagged-text event retained on-device for parent review. */
data class FlaggedTextEvent(
    val phrase: String,
    val appName: String,
    val category: String,
    val timestamp: Long
)
