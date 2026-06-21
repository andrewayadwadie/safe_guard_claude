package com.safeguard.parentalcontrol.util

import com.google.gson.*
import timber.log.Timber
import java.lang.reflect.Type
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

/**
 * Flexible Gson TypeAdapter for Date that handles multiple ISO 8601 formats
 *
 * Handles formats from backend:
 * - 2026-01-22T14:31:00.165515 (microseconds, no timezone)
 * - 2026-01-22T14:31:00.165 (milliseconds, no timezone)
 * - 2026-01-22T14:31:00 (no subseconds, no timezone)
 * - 2026-01-22T14:31:00Z (UTC timezone)
 * - 2026-01-22T14:31:00+02:00 (offset timezone)
 * - 2026-01-22T14:31:00.165Z (milliseconds with UTC)
 * - 2026-01-22 (date only)
 */
class FlexibleDateAdapter : JsonDeserializer<Date>, JsonSerializer<Date> {

    companion object {
        private const val TAG = "FlexibleDateAdapter"

        // Date formats to try, in order of preference
        private val DATE_FORMATS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",      // Microseconds, no timezone
            "yyyy-MM-dd'T'HH:mm:ss.SSS",          // Milliseconds, no timezone
            "yyyy-MM-dd'T'HH:mm:ss",              // No subseconds, no timezone
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",    // Microseconds with timezone offset
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",       // Milliseconds with timezone offset
            "yyyy-MM-dd'T'HH:mm:ssXXX",           // No subseconds with timezone offset
            "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",    // Microseconds with UTC
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",       // Milliseconds with UTC
            "yyyy-MM-dd'T'HH:mm:ss'Z'",           // No subseconds with UTC
            "yyyy-MM-dd"                          // Date only
        )

        // Thread-local formatters for thread safety
        private val formatters: ThreadLocal<MutableMap<String, SimpleDateFormat>> =
            ThreadLocal.withInitial { mutableMapOf() }

        private fun getFormatter(pattern: String): SimpleDateFormat {
            val cache = formatters.get()!!
            return cache.getOrPut(pattern) {
                SimpleDateFormat(pattern, Locale.US).apply {
                    // Use UTC timezone for parsing dates without timezone info
                    // This ensures consistent behavior across devices
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }
            }
        }
    }

    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): Date? {
        if (json == null || json.isJsonNull) {
            return null
        }

        val dateString = json.asString
        if (dateString.isBlank()) {
            return null
        }

        // Normalize the date string
        val normalizedDate = normalizeDate(dateString)

        // Try each format
        for (pattern in DATE_FORMATS) {
            try {
                val formatter = getFormatter(pattern)
                return formatter.parse(normalizedDate)
            } catch (e: ParseException) {
                // Try next format
            }
        }

        // If all formats fail, log and return null or throw
        Timber.tag(TAG).w("Failed to parse date: $dateString (normalized: $normalizedDate)")

        // As a last resort, try to parse just the date part if it contains 'T'
        if (normalizedDate.contains('T')) {
            try {
                val datePart = normalizedDate.substringBefore('T')
                return getFormatter("yyyy-MM-dd").parse(datePart)
            } catch (e: ParseException) {
                // Ignore
            }
        }

        throw JsonParseException("Unable to parse date: $dateString")
    }

    override fun serialize(
        src: Date?,
        typeOfSrc: Type?,
        context: JsonSerializationContext?
    ): JsonElement {
        if (src == null) {
            return JsonNull.INSTANCE
        }

        // Serialize in ISO 8601 format WITH LOCAL TIMEZONE OFFSET
        // This is critical for screen time dates - we want to preserve the LOCAL date
        // e.g., Jan 22 00:00 in Israel (UTC+2) should be sent as "2026-01-22T00:00:00+02:00"
        // NOT as "2026-01-21T22:00:00Z" which would lose the local date intent
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault() // Use device's local timezone
        }
        return JsonPrimitive(formatter.format(src))
    }

    /**
     * Normalize date string to handle edge cases
     */
    private fun normalizeDate(dateString: String): String {
        var normalized = dateString.trim()

        // Handle timezone formats
        // Replace 'Z' at the end with '+00:00' for consistent parsing
        if (normalized.endsWith("Z") || normalized.endsWith("z")) {
            normalized = normalized.dropLast(1) + "+00:00"
        }

        // Handle microseconds - SimpleDateFormat SSS only handles milliseconds
        // So we truncate microseconds to milliseconds (6 digits to 3)
        val microsecondsRegex = """(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})\.(\d{6})(.*)""".toRegex()
        val microMatch = microsecondsRegex.matchEntire(normalized)
        if (microMatch != null) {
            val (datetime, micros, rest) = microMatch.destructured
            // Take first 3 digits (milliseconds) and ignore the rest
            normalized = "$datetime.${micros.take(3)}$rest"
        }

        return normalized
    }
}
