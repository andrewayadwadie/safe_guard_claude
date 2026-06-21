package com.safeguard.parentalcontrol.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Extension functions for common operations
 *
 * Performance optimizations:
 * - ThreadLocal cached SimpleDateFormat instances
 * - Avoids creating new formatter objects on each call
 */

// ==================== ThreadLocal Date Formatters ====================

/**
 * ThreadLocal cache for SimpleDateFormat instances
 * SimpleDateFormat is not thread-safe, so each thread gets its own instance
 */
private object DateFormatters {
    val dateFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    }
    val dateTimeFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    }
    val timeFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    }
    val isoDateFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }
}

// ==================== Time Formatting Extensions ====================

fun Int.formatAsTime(): String {
    val hours = this / 3600
    val minutes = (this % 3600) / 60
    val seconds = this % 60

    return when {
        hours > 0 -> String.format("%dh %02dm", hours, minutes)
        minutes > 0 -> String.format("%dm %02ds", minutes, seconds)
        else -> String.format("%ds", seconds)
    }
}

fun Long.formatAsTime(): String = this.toInt().formatAsTime()

fun Int.formatAsHoursMinutes(): String {
    val hours = this / 3600
    val minutes = (this % 3600) / 60

    return when {
        hours > 0 && minutes > 0 -> "$hours hr $minutes min"
        hours > 0 -> "$hours hr"
        minutes > 0 -> "$minutes min"
        else -> "< 1 min"
    }
}

// ==================== Date Formatting Extensions (Cached) ====================

/**
 * Format date as "MMM dd, yyyy" (e.g., "Jan 15, 2026")
 * Uses cached ThreadLocal formatter for performance
 */
fun Date.formatAsDate(): String {
    return DateFormatters.dateFormat.get()!!.format(this)
}

/**
 * Format date as "MMM dd, yyyy HH:mm" (e.g., "Jan 15, 2026 14:30")
 * Uses cached ThreadLocal formatter for performance
 */
fun Date.formatAsDateTime(): String {
    return DateFormatters.dateTimeFormat.get()!!.format(this)
}

/**
 * Format date as "HH:mm" (e.g., "14:30")
 * Uses cached ThreadLocal formatter for performance
 */
fun Date.formatAsTime(): String {
    return DateFormatters.timeFormat.get()!!.format(this)
}

/**
 * Format date as relative time (e.g., "5 minutes ago", "2 hours ago")
 */
fun Date.formatAsRelative(): String {
    val now = System.currentTimeMillis()
    val diff = now - this.time

    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${diff / TimeUnit.MINUTES.toMillis(1)} minutes ago"
        diff < TimeUnit.DAYS.toMillis(1) -> "${diff / TimeUnit.HOURS.toMillis(1)} hours ago"
        diff < TimeUnit.DAYS.toMillis(7) -> "${diff / TimeUnit.DAYS.toMillis(1)} days ago"
        else -> formatAsDate()
    }
}

/**
 * Check if date is today
 * Uses cached ThreadLocal formatter for performance
 */
fun Date.isToday(): Boolean {
    val formatter = DateFormatters.isoDateFormat.get()!!
    return formatter.format(this) == formatter.format(Date())
}

/**
 * Get start of day (midnight)
 * Uses cached ThreadLocal formatter for performance
 */
fun Date.startOfDay(): Date {
    val formatter = DateFormatters.isoDateFormat.get()!!
    return formatter.parse(formatter.format(this)) ?: this
}

// ==================== Context Extensions ====================

fun Context.showToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

fun Context.isNetworkAvailable(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

fun Context.getAppName(packageName: String): String? {
    return try {
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(applicationInfo).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

fun Context.isAppInstalled(packageName: String): Boolean {
    return try {
        packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

// ==================== String Extensions ====================

fun String?.orEmpty(): String = this ?: ""

fun String?.isValidEmail(): Boolean {
    return this != null && android.util.Patterns.EMAIL_ADDRESS.matcher(this).matches()
}

fun String?.isValidPassword(): Boolean {
    return this != null && this.length >= 8
}

/**
 * Check if string contains potentially dangerous characters
 * Used for basic input sanitization
 */
fun String.containsUnsafeCharacters(): Boolean {
    val unsafePatterns = listOf(
        "<script", "</script", "javascript:", "onclick", "onerror",
        "DROP TABLE", "DELETE FROM", "INSERT INTO", "--", "/*", "*/"
    )
    val lowerCase = this.lowercase()
    return unsafePatterns.any { lowerCase.contains(it.lowercase()) }
}

// ==================== Number Extensions ====================

fun Float.toPercentage(): String = "${(this * 100).toInt()}%"

fun Double.toPercentage(): String = "${(this * 100).toInt()}%"

// ==================== Collection Extensions ====================

fun <T> List<T>.safeSubList(fromIndex: Int, toIndex: Int): List<T> {
    val safeFrom = fromIndex.coerceAtLeast(0)
    val safeTo = toIndex.coerceAtMost(size)
    return if (safeFrom >= safeTo) emptyList() else subList(safeFrom, safeTo)
}
