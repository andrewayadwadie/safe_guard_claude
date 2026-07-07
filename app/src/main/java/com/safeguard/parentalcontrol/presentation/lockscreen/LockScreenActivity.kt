package com.safeguard.parentalcontrol.presentation.lockscreen

import androidx.compose.ui.tooling.preview.Preview

import androidx.compose.ui.res.stringResource
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.util.LocaleHelper

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telephony.TelephonyManager
import timber.log.Timber
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardAnimations
import com.safeguard.parentalcontrol.presentation.theme.HarisPetrol
import com.safeguard.parentalcontrol.presentation.theme.HarisPetrolDark
import com.safeguard.parentalcontrol.presentation.theme.HarisGold
import com.safeguard.parentalcontrol.presentation.theme.SemanticColors
import com.safeguard.parentalcontrol.data.model.AlertSeverity
import com.safeguard.parentalcontrol.data.model.AlertType
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import javax.inject.Inject
import kotlinx.coroutines.launch

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

    @Inject
    lateinit var alertRepository: AlertRepository

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    /**
     * Send a request from the child device to the parent. Reuses the existing alert
     * pipeline (POST /alerts → FCM to linked parent devices), so no new backend is needed.
     * Fire-and-forget: the child's UI confirms optimistically; failures are logged.
     */
    private fun sendParentRequest(alertType: AlertType, title: String, message: String, lockTypeMeta: String) {
        lifecycleScope.launch {
            try {
                alertRepository.createAlert(
                    alertType = alertType,
                    severity = AlertSeverity.MEDIUM,
                    title = title,
                    message = message,
                    metadata = mapOf("source" to "lock_screen_request", "lock_type" to lockTypeMeta)
                )
            } catch (e: Exception) {
                Timber.e("Failed to send parent request: ${e.message}")
            }
        }
    }

    // Handler for delayed restart - keep reference to cancel pending restarts
    private val restartHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingRestartRunnable: Runnable? = null

    /**
     * Open the system dialer so the child can place an emergency call even while the
     * device is locked. Uses ACTION_DIAL (no CALL_PHONE permission, no auto-dial) —
     * the dialer is whitelisted in [phoneDialerPackages], so the lock screen yields
     * to it via shouldAllowExit() once it's foreground.
     */
    private fun launchEmergencyDialer() {
        try {
            startActivity(
                Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (e: Exception) {
            Timber.e("Failed to open emergency dialer: ${e.message}")
        }
    }

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
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: getString(R.string.lock_default_message)
        val appName = intent.getStringExtra(EXTRA_APP_NAME)

        // Track that we're showing for this lock type
        setShowingForType(lockType)

        setContent {
            SafeGuardTheme {
                LockScreen(
                    lockType = lockType,
                    message = message,
                    appName = appName,
                    onDismiss = {
                        // Only reachable from a paused-app lock's "Okay": let the child
                        // back out so their other apps stay usable.
                        LockScreenActivity.allowDismiss()
                        finish()
                    },
                    onRequestMoreTime = {
                        sendParentRequest(
                            AlertType.SCREEN_TIME_LIMIT,
                            getString(R.string.lock_request_more_time_title),
                            getString(R.string.lock_request_more_time_body),
                            lockType
                        )
                    },
                    onAskParent = {
                        sendParentRequest(
                            AlertType.APP_BLOCKED,
                            getString(R.string.lock_request_blocked_title),
                            getString(
                                R.string.lock_request_blocked_body,
                                appName ?: getString(R.string.lock_a_blocked_app)
                            ),
                            lockType
                        )
                    },
                    onEmergencyCall = { launchEmergencyDialer() }
                )
            }
        }
    }

    // Prevent back button from closing the lock screen. Intentionally does NOT
    // call super: the whole point of the lock screen is that Back cannot dismiss it.
    @SuppressLint("MissingSuperCall")
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
                Timber.d("Phone call active: state=$callState")
            }
            isActive
        } catch (e: Exception) {
            Timber.e("Error checking phone call state: ${e.message}")
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
                    Timber.d("Phone/dialer app in foreground: $lastForegroundApp")
                    return true
                }
            }
        } catch (e: Exception) {
            Timber.e("Error checking foreground app via UsageStats: ${e.message}")
        }

        // Method 2: Check running tasks (fallback, may be limited on newer Android)
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            @Suppress("DEPRECATION")
            val runningTasks = activityManager?.getRunningTasks(1)
            if (!runningTasks.isNullOrEmpty()) {
                val topActivity = runningTasks[0].topActivity?.packageName
                if (topActivity != null && phoneDialerPackages.contains(topActivity)) {
                    Timber.d("Phone/dialer app on top: $topActivity")
                    return true
                }
            }
        } catch (e: Exception) {
            Timber.e("Error checking running tasks: ${e.message}")
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
            Timber.d("Screen is off - will not restart lock screen")
            return true
        }

        // Check if keyguard (device lock screen) is showing
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: false
        if (isKeyguardLocked) {
            Timber.d("Device is locked - will not restart lock screen")
            return true
        }

        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // When receiving a new intent (due to SINGLE_TOP), we're already in foreground
        // Just cancel any pending restart
        Timber.d("onNewIntent received - already showing, canceling any pending restart")
        cancelPendingRestart()
        isInForeground = true
    }

    override fun onResume() {
        try {
            super.onResume()
        } catch (e: Exception) {
            Timber.e("Error in onResume: ${e.message}")
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
            Timber.d("Lock screen destroyed and finished - cleared showing type")
        }
    }

    override fun onPause() {
        try {
            super.onPause()
        } catch (e: Exception) {
            Timber.e("Error in onPause: ${e.message}")
        }

        // Mark as not in foreground
        isInForeground = false

        // Check if we should allow exit (dismiss flag, phone call, or dialer app)
        if (shouldAllowExit()) {
            Timber.d("Allowing exit - phone/dialer detected or dismiss requested")
            cancelPendingRestart()
            if (shouldDismiss) {
                resetDismiss()
                try {
                    finish()
                } catch (e: Exception) {
                    Timber.e("Error finishing activity: ${e.message}")
                }
            }
            return
        }

        // Cancel any existing pending restart before scheduling new one
        cancelPendingRestart()

        // If screen is off or device is locked, do NOT restart - let the user sleep!
        if (isScreenOffOrLocked()) {
            Timber.d("Screen off or device locked - not scheduling restart from onPause")
            return
        }

        // Schedule a restart with delay - only if not already showing
        pendingRestartRunnable = Runnable {
            try {
                // Re-check all conditions after delay
                // Also check isInForeground to prevent restart if we're already back
                if (!shouldAllowExit() && !isFinishing && !isInForeground && !isScreenOffOrLocked()) {
                    Timber.d("Restarting lock screen from onPause")
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
                Timber.e("Error restarting lock screen: ${e.message}")
            }
        }
        restartHandler.postDelayed(pendingRestartRunnable!!, 500) // Longer delay to prevent rapid restarts
    }

    override fun onStop() {
        try {
            super.onStop()
        } catch (e: Exception) {
            Timber.e("Error in onStop: ${e.message}")
        }

        // Check if we should allow exit
        if (shouldAllowExit()) {
            Timber.d("Allowing exit in onStop - phone/dialer detected or dismiss requested")
            cancelPendingRestart()
            return
        }

        // Don't schedule another restart from onStop if onPause already did
        // This prevents double restarts
        if (pendingRestartRunnable != null) {
            Timber.d("Restart already pending from onPause, skipping onStop restart")
            return
        }

        // If screen is off or device is locked, do NOT restart - let the user sleep!
        if (isScreenOffOrLocked()) {
            Timber.d("Screen off or device locked - not scheduling restart from onStop")
            return
        }

        // Only restart from onStop if we're completely stopped and not finishing
        if (!isFinishing && !isInForeground) {
            pendingRestartRunnable = Runnable {
                try {
                    // Re-check all conditions after delay
                    if (!shouldAllowExit() && !isInForeground && !isScreenOffOrLocked()) {
                        Timber.d("Restarting lock screen from onStop")
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
                    Timber.e("Error restarting lock screen from onStop: ${e.message}")
                }
            }
            restartHandler.postDelayed(pendingRestartRunnable!!, 1000) // Longer delay for onStop
        }
    }

    companion object {
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

/**
 * The lock UI. Top-level + `internal` so it can be hosted both by [LockScreenActivity]
 * (the no-overlay-permission / full-screen-intent fallback) and by the system-overlay
 * lock window ([com.safeguard.parentalcontrol.presentation.lockscreen.LockOverlayController]),
 * which renders above PiP / freeform / pop-up windows an Activity cannot cover.
 */
@Composable
internal fun LockScreen(
    lockType: String,
    message: String,
    appName: String?,
    onDismiss: (() -> Unit)? = null,
    onRequestMoreTime: (() -> Unit)? = null,
    onAskParent: (() -> Unit)? = null,
    onEmergencyCall: (() -> Unit)? = null,
) {
    // The accepted Warm Hearth child design (DESIGN.md §5). Time/bedtime/parent locks are
    // Pine-DRENCHED (the surface IS the calm) with a translucent halo and an unlock-time
    // pill; app blocks are the lighter "block" dialect (clay halo on linen) since the child
    // can still use other apps. Never a punitive red wash, never shaming language.
    val app = appName ?: stringResource(R.string.lock_this_app)
    val spec = when (lockType) {
        LockScreenActivity.LOCK_TYPE_DAILY_LIMIT -> LockSpec(
            icon = Icons.Default.Timer,
            title = stringResource(R.string.lock_daily_title),
            description = stringResource(R.string.lock_daily_desc),
            meta = stringResource(R.string.lock_daily_meta),
            accentLabel = stringResource(R.string.lock_ask_more_time)
        )
        LockScreenActivity.LOCK_TYPE_BEDTIME -> LockSpec(
            icon = Icons.Default.Bedtime,
            title = stringResource(R.string.lock_bedtime_title),
            description = stringResource(R.string.lock_bedtime_desc),
            meta = stringResource(R.string.lock_bedtime_meta),
            deepBedtime = true
        )
        LockScreenActivity.LOCK_TYPE_STUDY_TIME -> LockSpec(
            icon = Icons.Default.School,
            title = stringResource(R.string.lock_study_title),
            description = stringResource(R.string.lock_study_desc),
            meta = stringResource(R.string.lock_study_meta)
        )
        LockScreenActivity.LOCK_TYPE_APP_BLOCKED -> LockSpec(
            icon = Icons.Default.Block,
            title = if (appName != null) {
                stringResource(R.string.lock_app_paused_title, appName)
            } else {
                stringResource(R.string.lock_app_paused_title_generic)
            },
            description = stringResource(R.string.lock_app_paused_desc),
            primaryLabel = stringResource(R.string.lock_okay),
            ghostLabel = stringResource(R.string.lock_ask_parent)
        )
        LockScreenActivity.LOCK_TYPE_APP_LIMIT -> LockSpec(
            icon = Icons.Default.Timer,
            title = if (appName != null) {
                stringResource(R.string.lock_app_limit_title, appName)
            } else {
                stringResource(R.string.lock_app_limit_title_generic)
            },
            description = stringResource(R.string.lock_app_limit_desc, app),
            primaryLabel = stringResource(R.string.lock_okay),
            ghostLabel = stringResource(R.string.lock_ask_parent)
        )
        LockScreenActivity.LOCK_TYPE_PARENT_LOCKED -> LockSpec(
            icon = Icons.Default.PhonelinkLock,
            title = stringResource(R.string.lock_device_locked),
            description = message.ifEmpty { stringResource(R.string.lock_parent_locked_desc) }
        )
        else -> LockSpec(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.lock_device_locked),
            description = message.ifEmpty { stringResource(R.string.lock_generic_desc) }
        )
    }

    val cream = Color(0xFFFCFAF5)
    val onPineMuted = cream.copy(alpha = 0.86f)

    // Entrance: each element eases up into place once the screen first appears.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val riseUpPx = with(LocalDensity.current) { 14.dp.toPx() }

    // The halo breathes very slowly so the screen feels tended, never urgent or alarmed.
    val breath = rememberInfiniteTransition(label = "lock_breath")
    val haloScale by breath.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = SafeGuardAnimations.EaseInOutSoft),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lock_halo_scale"
    )
    val glowAlpha by breath.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = SafeGuardAnimations.EaseInOutSoft),
            repeatMode = RepeatMode.Reverse
        ),
        label = "lock_halo_glow"
    )

    val background: Brush = when {
        spec.pine && spec.deepBedtime ->
            Brush.verticalGradient(listOf(Color(0xFF244A3E), Color(0xFF1C3F35), Color(0xFF16332B)))
        spec.pine ->
            Brush.verticalGradient(listOf(HarisPetrol, HarisPetrolDark, Color(0xFF1C3F35)))
        else ->
            Brush.verticalGradient(listOf(Color(0xFFF6EADB), MaterialTheme.colorScheme.background))
    }
    val titleColor = if (spec.pine) cream else Color(0xFF1C2022)
    val bodyColor = if (spec.pine) onPineMuted else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 34.dp, vertical = 36.dp)
        ) {
            // Halo: a soft translucent ring on pine, a warm Clay ring on the lighter block screens.
            val pHalo = rememberEntrance(visible, delayMillis = 0)
            val haloBg = if (spec.pine) cream.copy(alpha = 0.10f) else SemanticColors.childBackground
            val haloBorder = if (spec.pine) cream.copy(alpha = 0.18f) else Color.Transparent
            val iconTint = if (spec.pine) cream else SemanticColors.childPrimary
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .graphicsLayer {
                        alpha = pHalo
                        translationY = (1f - pHalo) * riseUpPx
                    }
                    .drawBehind {
                        if (spec.pine) {
                            val radius = size.minDimension * 0.5f * haloScale
                            val center = Offset(size.width / 2f, size.height / 2f)
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(cream.copy(alpha = glowAlpha), cream.copy(alpha = 0f)),
                                    center = center,
                                    radius = radius
                                ),
                                radius = radius,
                                center = center
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(104.dp)
                        .graphicsLayer { scaleX = haloScale; scaleY = haloScale }
                        .clip(CircleShape)
                        .background(haloBg)
                        .border(1.dp, haloBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = null,
                        modifier = Modifier.size(46.dp),
                        tint = iconTint
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            val pTitle = rememberEntrance(visible, delayMillis = 130)
            Text(
                text = spec.title,
                style = MaterialTheme.typography.headlineMedium,
                color = titleColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.entranceRise(pTitle, riseUpPx)
            )

            Spacer(modifier = Modifier.height(12.dp))

            val pDesc = rememberEntrance(visible, delayMillis = 210)
            Text(
                text = spec.description,
                style = MaterialTheme.typography.bodyLarge,
                color = bodyColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.entranceRise(pDesc, riseUpPx)
            )

            // Unlock-time pill — tells the child plainly when the device comes back.
            if (spec.meta != null) {
                Spacer(modifier = Modifier.height(18.dp))
                val pMeta = rememberEntrance(visible, delayMillis = 290)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .entranceRise(pMeta, riseUpPx)
                        .clip(RoundedCornerShape(999.dp))
                        .background(cream.copy(alpha = 0.12f))
                        .padding(horizontal = 15.dp, vertical = 9.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WbSunny,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = cream
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = spec.meta,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = cream
                    )
                }
            }

            // Actions. "Okay" dismisses a paused-app lock so other apps stay usable.
            // "Ask for more time" / "Ask a parent" send a real request to the parent
            // (reuses the alert pipeline); the child gets an optimistic confirmation.
            if (spec.accentLabel != null || spec.primaryLabel != null || spec.ghostLabel != null) {
                Spacer(modifier = Modifier.height(22.dp))
                var requestSent by remember { mutableStateOf(false) }
                val pAct = rememberEntrance(visible, delayMillis = 360)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.entranceRise(pAct, riseUpPx)
                ) {
                    if (spec.accentLabel != null && !requestSent) {
                        Button(
                            onClick = { onRequestMoreTime?.invoke(); requestSent = true },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = HarisGold,
                                contentColor = Color(0xFF1C2022)
                            ),
                            modifier = Modifier.shadow(
                                elevation = 14.dp,
                                shape = RoundedCornerShape(14.dp),
                                spotColor = HarisGold,
                                ambientColor = HarisGold
                            )
                        ) {
                            Text(spec.accentLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (spec.primaryLabel != null) {
                        // Cream fill on the pine-drenched surface (a pine button would vanish).
                        Button(
                            onClick = { onDismiss?.invoke() },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = cream,
                                contentColor = HarisPetrol
                            )
                        ) {
                            Text(spec.primaryLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (spec.ghostLabel != null && !requestSent) {
                        TextButton(onClick = { onAskParent?.invoke(); requestSent = true }) {
                            Text(
                                spec.ghostLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (spec.pine) cream else HarisPetrol
                            )
                        }
                    }
                    if (requestSent) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (spec.pine) cream else SemanticColors.success
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.lock_request_sent),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (spec.pine) cream else SemanticColors.success
                            )
                        }
                    }
                }
            }

            // Emergency call — always available, on every lock type. Opens the system
            // dialer so a child is never blocked from reaching help while locked.
            if (onEmergencyCall != null) {
                Spacer(modifier = Modifier.height(28.dp))
                val pEmergency = rememberEntrance(visible, delayMillis = 410)
                val emergencyColor = if (spec.pine) cream else HarisPetrol
                OutlinedButton(
                    onClick = { onEmergencyCall.invoke() },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, emergencyColor.copy(alpha = 0.45f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = emergencyColor),
                    modifier = Modifier.entranceRise(pEmergency, riseUpPx)
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = emergencyColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.lock_emergency_call),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            val pBrand = rememberEntrance(visible, delayMillis = 450)
            Text(
                text = stringResource(R.string.lock_protected_by),
                style = MaterialTheme.typography.labelMedium,
                color = if (spec.pine) onPineMuted.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.entranceRise(pBrand, riseUpPx)
            )
        }
    }
}

@Preview(name = "Lock · Light", showBackground = true)
@Composable private fun LockScreenLightPreview() { SafeGuardTheme(darkTheme = false) { LockScreen(LockScreenActivity.LOCK_TYPE_DAILY_LIMIT, "", null) } }
@Preview(name = "Lock · Dark", showBackground = true)
@Composable private fun LockScreenDarkPreview() { SafeGuardTheme(darkTheme = true) { LockScreen(LockScreenActivity.LOCK_TYPE_BEDTIME, "", null) } }
private data class LockSpec(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val meta: String? = null,
    val pine: Boolean = true,
    val deepBedtime: Boolean = false,
    val accentLabel: String? = null,
    val primaryLabel: String? = null,
    val ghostLabel: String? = null,
)

/**
 * Eases a single element from 0 to 1 once [visible] flips, after [delayMillis]. Staggering
 * these across the column gives the calm settle, top to bottom, instead of a hard cut-in.
 */
@Composable
private fun rememberEntrance(visible: Boolean, delayMillis: Int): Float {
    val p by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = 520,
            delayMillis = delayMillis,
            easing = SafeGuardAnimations.EaseOutQuart
        ),
        label = "lock_entrance_$delayMillis"
    )
    return p
}

/** Fade + a short upward rise, driven by an entrance progress value. */
private fun Modifier.entranceRise(progress: Float, riseUpPx: Float): Modifier =
    this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * riseUpPx
    }
