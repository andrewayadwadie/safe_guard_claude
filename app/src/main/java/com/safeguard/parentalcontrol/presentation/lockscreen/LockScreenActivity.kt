package com.safeguard.parentalcontrol.presentation.lockscreen

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Lock Screen Activity
 *
 * Displays a full-screen blocking overlay when screen time limits are exceeded,
 * during bedtime hours, or when a blocked app is detected.
 *
 * This activity:
 * - Covers the entire screen
 * - Cannot be dismissed by the child
 * - Shows reason for lock and parent contact info
 * - Allows phone calls (incoming and outgoing)
 */
@AndroidEntryPoint
class LockScreenActivity : ComponentActivity() {

    // Handler for delayed restart - keep reference to cancel pending restarts
    private val restartHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingRestartRunnable: Runnable? = null

    // Track if activity is in foreground to prevent restart loops
    private var isInForeground = false

    // Whitelisted phone/dialer apps - these should ALWAYS be allowed
    private val phoneDialerPackages = setOf(
        "com.android.phone",
        "com.android.dialer",
        "com.android.incallui",
        "com.android.server.telecom",
        "com.google.android.dialer",
        "com.samsung.android.incallui",
        "com.samsung.android.dialer",
        "com.samsung.android.contacts",
        "com.oneplus.dialer",
        "com.oneplus.contacts",
        "com.huawei.contacts",
        "com.miui.contacts",
        "com.sec.android.app.telephonyui",  // Samsung call UI
        "com.android.contacts",              // Stock contacts
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if this is a re-show (from onPause/onStop) or initial show
        val isReshow = intent.getBooleanExtra(EXTRA_IS_RESHOW, false)

        // Make this activity appear over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            // Only turn screen on for initial show, not re-shows (saves battery)
            if (!isReshow) {
                setTurnScreenOn(true)
            }
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            if (!isReshow) {
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            }
        }

        // Make activity harder to dismiss
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)

        // Prevent screenshots of lock screen
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val lockType = intent.getStringExtra(EXTRA_LOCK_TYPE) ?: LOCK_TYPE_DAILY_LIMIT
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Screen time limit reached"
        val appName = intent.getStringExtra(EXTRA_APP_NAME)

        // Track that we're showing for this lock type
        setShowingForType(lockType)

        setContent {
            SafeGuardTheme {
                LockScreen(
                    lockType = lockType,
                    message = message,
                    appName = appName
                )
            }
        }
    }

    // Prevent back button from closing the lock screen
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing - prevent dismissing the lock screen
    }

    /**
     * Check if a phone call is currently active or ringing
     */
    @Suppress("DEPRECATION")
    private fun isPhoneCallActive(): Boolean {
        return try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val callState = telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE
            val isActive = callState != TelephonyManager.CALL_STATE_IDLE
            if (isActive) {
                Log.d(TAG, "Phone call active: state=$callState")
            }
            isActive
        } catch (e: Exception) {
            Log.e(TAG, "Error checking phone call state: ${e.message}")
            false
        }
    }

    /**
     * Check if a phone/dialer app is currently in foreground or recently launched
     */
    private fun isPhoneAppInForeground(): Boolean {
        // Method 1: Check using UsageStatsManager (most reliable)
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usageStatsManager != null) {
                val endTime = System.currentTimeMillis()
                val startTime = endTime - 5000 // Last 5 seconds
                val events = usageStatsManager.queryEvents(startTime, endTime)
                val event = android.app.usage.UsageEvents.Event()

                var lastForegroundApp: String? = null
                var lastForegroundTime = 0L

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                        if (event.timeStamp > lastForegroundTime) {
                            lastForegroundTime = event.timeStamp
                            lastForegroundApp = event.packageName
                        }
                    }
                }

                if (lastForegroundApp != null && phoneDialerPackages.contains(lastForegroundApp)) {
                    Log.d(TAG, "Phone/dialer app in foreground: $lastForegroundApp")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking foreground app via UsageStats: ${e.message}")
        }

        // Method 2: Check running tasks (fallback, may be limited on newer Android)
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            @Suppress("DEPRECATION")
            val runningTasks = activityManager?.getRunningTasks(1)
            if (!runningTasks.isNullOrEmpty()) {
                val topActivity = runningTasks[0].topActivity?.packageName
                if (topActivity != null && phoneDialerPackages.contains(topActivity)) {
                    Log.d(TAG, "Phone/dialer app on top: $topActivity")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking running tasks: ${e.message}")
        }

        return false
    }

    /**
     * Check if we should allow the user to leave (phone call or dialer)
     */
    private fun shouldAllowExit(): Boolean {
        if (shouldDismiss) return true
        if (isPhoneCallActive()) return true
        if (isPhoneAppInForeground()) return true
        return false
    }

    /**
     * Check if the screen is currently off or the device is locked
     * We should NOT restart the lock screen activity when screen is off - let the user sleep!
     */
    private fun isScreenOffOrLocked(): Boolean {
        // Check if screen is off
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenOn = powerManager?.isInteractive ?: true
        if (!isScreenOn) {
            Log.d(TAG, "Screen is off - will not restart lock screen")
            return true
        }

        // Check if keyguard (device lock screen) is showing
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: false
        if (isKeyguardLocked) {
            Log.d(TAG, "Device is locked - will not restart lock screen")
            return true
        }

        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // When receiving a new intent (due to SINGLE_TOP), we're already in foreground
        // Just cancel any pending restart
        Log.d(TAG, "onNewIntent received - already showing, canceling any pending restart")
        cancelPendingRestart()
        isInForeground = true
    }

    override fun onResume() {
        try {
            super.onResume()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume: ${e.message}")
        }

        // Mark as in foreground - cancel any pending restart since we're already showing
        isInForeground = true
        cancelPendingRestart()

        // Check if we should dismiss
        if (shouldDismiss) {
            resetDismiss()
            finish()
        }
    }

    /**
     * Cancel any pending restart runnable to prevent restart loops
     */
    private fun cancelPendingRestart() {
        pendingRestartRunnable?.let {
            restartHandler.removeCallbacks(it)
            pendingRestartRunnable = null
        }
    }

    override fun onDestroy() {
        // Cancel any pending restarts
        cancelPendingRestart()

        super.onDestroy()

        // Clear the showing type when activity is destroyed
        // But only if we're not being recreated (e.g., rotation)
        if (isFinishing) {
            setShowingForType(null)
            Log.d(TAG, "Lock screen destroyed and finished - cleared showing type")
        }
    }

    override fun onPause() {
        try {
            super.onPause()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPause: ${e.message}")
        }

        // Mark as not in foreground
        isInForeground = false

        // Check if we should allow exit (dismiss flag, phone call, or dialer app)
        if (shouldAllowExit()) {
            Log.d(TAG, "Allowing exit - phone/dialer detected or dismiss requested")
            cancelPendingRestart()
            if (shouldDismiss) {
                resetDismiss()
                try {
                    finish()
                } catch (e: Exception) {
                    Log.e(TAG, "Error finishing activity: ${e.message}")
                }
            }
            return
        }

        // Cancel any existing pending restart before scheduling new one
        cancelPendingRestart()

        // If screen is off or device is locked, do NOT restart - let the user sleep!
        if (isScreenOffOrLocked()) {
            Log.d(TAG, "Screen off or device locked - not scheduling restart from onPause")
            return
        }

        // Schedule a restart with delay - only if not already showing
        pendingRestartRunnable = Runnable {
            try {
                // Re-check all conditions after delay
                // Also check isInForeground to prevent restart if we're already back
                if (!shouldAllowExit() && !isFinishing && !isInForeground && !isScreenOffOrLocked()) {
                    Log.d(TAG, "Restarting lock screen from onPause")
                    val intent = Intent(this, LockScreenActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        // Mark as re-show so we don't turn screen on again
                        putExtra(EXTRA_IS_RESHOW, true)
                        // Preserve the lock type and message
                        putExtra(EXTRA_LOCK_TYPE, this@LockScreenActivity.intent.getStringExtra(EXTRA_LOCK_TYPE))
                        putExtra(EXTRA_MESSAGE, this@LockScreenActivity.intent.getStringExtra(EXTRA_MESSAGE))
                        putExtra(EXTRA_APP_NAME, this@LockScreenActivity.intent.getStringExtra(EXTRA_APP_NAME))
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting lock screen: ${e.message}")
            }
        }
        restartHandler.postDelayed(pendingRestartRunnable!!, 500) // Longer delay to prevent rapid restarts
    }

    override fun onStop() {
        try {
            super.onStop()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStop: ${e.message}")
        }

        // Check if we should allow exit
        if (shouldAllowExit()) {
            Log.d(TAG, "Allowing exit in onStop - phone/dialer detected or dismiss requested")
            cancelPendingRestart()
            return
        }

        // Don't schedule another restart from onStop if onPause already did
        // This prevents double restarts
        if (pendingRestartRunnable != null) {
            Log.d(TAG, "Restart already pending from onPause, skipping onStop restart")
            return
        }

        // If screen is off or device is locked, do NOT restart - let the user sleep!
        if (isScreenOffOrLocked()) {
            Log.d(TAG, "Screen off or device locked - not scheduling restart from onStop")
            return
        }

        // Only restart from onStop if we're completely stopped and not finishing
        if (!isFinishing && !isInForeground) {
            pendingRestartRunnable = Runnable {
                try {
                    // Re-check all conditions after delay
                    if (!shouldAllowExit() && !isInForeground && !isScreenOffOrLocked()) {
                        Log.d(TAG, "Restarting lock screen from onStop")
                        val intent = Intent(this, LockScreenActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            // Mark as re-show
                            putExtra(EXTRA_IS_RESHOW, true)
                            putExtra(EXTRA_LOCK_TYPE, this@LockScreenActivity.intent.getStringExtra(EXTRA_LOCK_TYPE))
                            putExtra(EXTRA_MESSAGE, this@LockScreenActivity.intent.getStringExtra(EXTRA_MESSAGE))
                            putExtra(EXTRA_APP_NAME, this@LockScreenActivity.intent.getStringExtra(EXTRA_APP_NAME))
                        }
                        startActivity(intent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error restarting lock screen from onStop: ${e.message}")
                }
            }
            restartHandler.postDelayed(pendingRestartRunnable!!, 1000) // Longer delay for onStop
        }
    }

    companion object {
        private const val TAG = "LockScreenActivity"

        const val EXTRA_LOCK_TYPE = "lock_type"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_IS_RESHOW = "is_reshow"

        const val LOCK_TYPE_DAILY_LIMIT = "daily_limit"
        const val LOCK_TYPE_BEDTIME = "bedtime"
        const val LOCK_TYPE_APP_BLOCKED = "app_blocked"
        const val LOCK_TYPE_APP_LIMIT = "app_limit"
        const val LOCK_TYPE_STUDY_TIME = "study_time"
        const val LOCK_TYPE_PARENT_LOCKED = "parent_locked"

        fun createIntent(
            context: Context,
            lockType: String,
            message: String,
            appName: String? = null
        ): Intent {
            return Intent(context, LockScreenActivity::class.java).apply {
                putExtra(EXTRA_LOCK_TYPE, lockType)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_IS_RESHOW, false) // Initial show
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }

        /**
         * Check if lock screen should be dismissed (called by enforcement service)
         */
        var shouldDismiss: Boolean = false
            private set

        /**
         * Track if lock screen is currently showing and for what reason
         * This prevents the screen from turning on repeatedly
         */
        var currentlyShowingLockType: String? = null
            private set

        fun allowDismiss() {
            shouldDismiss = true
            currentlyShowingLockType = null
        }

        fun resetDismiss() {
            shouldDismiss = false
        }

        /**
         * Check if the lock screen is already showing for the given type
         */
        fun isShowingForType(lockType: String): Boolean {
            return currentlyShowingLockType == lockType
        }

        /**
         * Mark the lock screen as showing for a specific type
         */
        fun setShowingForType(lockType: String?) {
            currentlyShowingLockType = lockType
        }
    }
}

@Composable
private fun LockScreen(
    lockType: String,
    message: String,
    appName: String?
) {
    val (icon, title, description) = when (lockType) {
        LockScreenActivity.LOCK_TYPE_DAILY_LIMIT -> Triple(
            Icons.Default.Timer,
            "Daily Limit Reached",
            "You've used all your screen time for today. The device will be available again tomorrow."
        )
        LockScreenActivity.LOCK_TYPE_BEDTIME -> Triple(
            Icons.Default.Bedtime,
            "Bedtime Mode",
            "It's time to rest. Device usage is restricted during bedtime hours."
        )
        LockScreenActivity.LOCK_TYPE_APP_BLOCKED -> Triple(
            Icons.Default.Block,
            "App Blocked",
            if (appName != null) "$appName has been blocked by your parent."
            else "This app has been blocked by your parent."
        )
        LockScreenActivity.LOCK_TYPE_APP_LIMIT -> Triple(
            Icons.Default.Lock,
            "App Limit Reached",
            if (appName != null) "You've reached the daily limit for $appName."
            else "You've reached the daily limit for this app."
        )
        LockScreenActivity.LOCK_TYPE_STUDY_TIME -> Triple(
            Icons.Default.School,
            "Study Time",
            "It's study time! Only educational apps are allowed right now."
        )
        LockScreenActivity.LOCK_TYPE_PARENT_LOCKED -> Triple(
            Icons.Default.PhonelinkLock,
            "Device Locked",
            message.ifEmpty { "Your parent has locked this device. It will be unlocked when they choose." }
        )
        else -> Triple(
            Icons.Default.Lock,
            "Device Locked",
            message
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // Lock Icon
            Surface(
                modifier = Modifier.size(120.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.error
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Title
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Description
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Additional message
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Need more time?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Ask your parent to adjust your screen time limits.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // SafeGuard branding
            Text(
                text = "Protected by SafeGuard",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f)
            )
        }
    }
}
