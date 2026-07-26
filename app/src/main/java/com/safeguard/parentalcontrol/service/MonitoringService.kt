package com.safeguard.parentalcontrol.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.SafeGuardApplication
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.data.repository.DeviceRepository
import com.safeguard.parentalcontrol.data.repository.FamilyRepository
import com.safeguard.parentalcontrol.data.repository.ScreenTimeRepository
import com.safeguard.parentalcontrol.data.repository.ScreenTimeRulesRepository
import com.safeguard.parentalcontrol.presentation.MainActivity
import com.safeguard.parentalcontrol.presentation.lockscreen.LockOverlayController
import com.safeguard.parentalcontrol.presentation.lockscreen.LockScreenActivity
import com.safeguard.parentalcontrol.util.Constants
import com.safeguard.parentalcontrol.util.LocaleHelper
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.getAppName
import com.safeguard.parentalcontrol.worker.ImageScanWorker
import com.safeguard.parentalcontrol.worker.PendingAlertWorker
import com.safeguard.parentalcontrol.worker.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service for continuous monitoring
 *
 * Optimized for battery efficiency:
 * - Uses WorkManager for periodic sync instead of polling
 * - Foreground service maintains app priority
 * - Proper CoroutineScope lifecycle management
 */
@AndroidEntryPoint
class MonitoringService : Service() {

    private fun getLocalizedString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(this).getString(resId, *args)

    @Inject
    lateinit var screenTimeRepository: ScreenTimeRepository

    @Inject
    lateinit var deviceRepository: DeviceRepository

    @Inject
    lateinit var alertRepository: AlertRepository

    @Inject
    lateinit var screenTimeRulesRepository: ScreenTimeRulesRepository

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var mediaFileObserver: MediaFileObserver

    @Inject
    lateinit var lockOverlayController: LockOverlayController

    @Inject
    lateinit var familyRepository: FamilyRepository

    // Proper CoroutineScope with lifecycle management
    private var serviceJob: Job? = null
    private val serviceScope: CoroutineScope
        get() = CoroutineScope(Dispatchers.Default + (serviceJob ?: SupervisorJob().also { serviceJob = it }))

    // Enforcement loop job
    private var enforcementJob: Job? = null

    // Doze-proof heartbeat. last_sync was updated ONLY by SyncWorker (WorkManager periodic),
    // which Doze defers to maintenance windows that can exceed the backend's 30-min offline
    // threshold - so an idle-but-healthy child device went silent overnight and the parent got
    // a false "Device offline" at 2am. A foreground service is exempt from Doze CPU limits, so
    // we heartbeat from here; the device now reads offline only when it is genuinely off, has no
    // network, or the service was actually killed.
    private var heartbeatJob: Job? = null
    private val HEARTBEAT_INTERVAL_MS = 10 * 60 * 1000L // 10 min (3 beats inside the 30-min window)
    // Adaptive intervals - fast when restrictions active, slow when idle
    private val ENFORCEMENT_CHECK_INTERVAL_FAST_MS = 3000L  // 3 seconds when restrictions active
    private val ENFORCEMENT_CHECK_INTERVAL_SLOW_MS = 15000L // 15 seconds when no restrictions
    private var restrictionsCurrentlyActive = false // Track if any restriction is being enforced

    // Track lock screen state - timestamp of last show to allow periodic re-enforcement
    private var lastLockScreenShowTime = 0L
    // Re-show quickly if child tries to bypass the lock screen
    private val LOCK_SCREEN_RESHOW_INTERVAL_MS = 1000L

    // Cache for app usage to avoid querying UsageStatsManager too frequently
    private val appUsageCache = mutableMapOf<String, Pair<Long, Int>>() // packageName -> (timestamp, usageSeconds)
    private val APP_USAGE_CACHE_DURATION_MS = 30000L // Cache app usage for 30 seconds
    private val APP_USAGE_CACHE_MAX_SIZE = 50 // Limit cache size to prevent memory bloat
    private var lastCacheCleanupTime = 0L
    private val CACHE_CLEANUP_INTERVAL_MS = 60000L // Clean cache every 60 seconds

    // Cache for foreground app detection - adaptive based on restrictions
    private var cachedForegroundApp: String? = null
    private var cachedForegroundAppTime = 0L
    private val FOREGROUND_APP_CACHE_FAST_MS = 1500L  // 1.5 seconds when restrictions active
    private val FOREGROUND_APP_CACHE_SLOW_MS = 5000L  // 5 seconds when idle

    // Track last SyncWorker enqueue to prevent excessive scheduling
    private var lastSyncWorkerEnqueueTime = 0L
    private val SYNC_WORKER_ENQUEUE_INTERVAL_MS = 300000L // Only re-enqueue every 5 minutes max

    // Network connectivity monitoring - re-sync immediately when connectivity is restored.
    // Without this, a child device that loses internet (Wi-Fi drop, dead zone, scheduled
    // router block) stays "offline" on the parent dashboard until the app is restarted,
    // because the periodic heartbeat does not re-fire on its own when the network returns.
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkSyncTime = 0L
    private val NETWORK_RESYNC_DEBOUNCE_MS = 10000L // avoid duplicate syncs on rapid network flaps

    // --- onTaskRemoved restart circuit-breaker + alert throttle ---
    // The task can be removed repeatedly in a tight cycle (lock-screen task re-parenting, OS
    // task cleanup), not just by a deliberate "swipe away". Restarting on a 1s exact alarm each
    // time and alerting the parent on every removal produced runaway CPU churn (load avg ~15)
    // and dozens of false "Monitoring Service Stopped" alerts/day (bursts of 13 in 2 min).
    // State is persisted because the cycle spans process deaths - an in-memory counter would
    // reset every iteration and never trip the breaker.
    private val RESTART_WINDOW_MS = 5 * 60 * 1000L                  // rolling window for restart accounting
    private val MAX_RESTARTS_PER_WINDOW = 3                         // beyond this, breaker opens
    private val RESTART_DELAY_MS = 5000L                            // restart backoff (was 1s)
    private val SERVICE_STOPPED_ALERT_COOLDOWN_MS = 15 * 60 * 1000L // mirror ProtectionMonitorWorker's gate
    private val KEY_RESTART_WINDOW_START = "monitoring_restart_window_start"
    private val KEY_RESTART_COUNT = "monitoring_restart_count"
    private val KEY_LAST_SERVICE_STOPPED_ALERT = "monitoring_last_service_stopped_alert"

    // Whitelisted phone/dialer apps - these should ALWAYS be allowed for emergency calls
    private val PHONE_DIALER_PACKAGES = setOf(
        "com.android.phone",           // Android Phone app
        "com.android.dialer",          // Android Dialer
        "com.android.incallui",        // Android In-call UI
        "com.android.server.telecom",  // Telecom service
        "com.google.android.dialer",   // Google Phone app
        "com.samsung.android.incallui",// Samsung In-call UI
        "com.samsung.android.dialer",  // Samsung Dialer
        "com.samsung.android.contacts",// Samsung Contacts (for dialing)
        "com.oneplus.dialer",          // OnePlus Dialer
        "com.huawei.contacts",         // Huawei Contacts/Dialer
        "com.miui.contacts",           // Xiaomi MIUI Contacts
    )

    override fun onCreate() {
        super.onCreate()
        serviceJob = SupervisorJob()
        registerNetworkCallback()
        // Anything held from a previous run (offline detection, backend outage, process
        // death) gets a delivery attempt as soon as monitoring comes up.
        PendingAlertWorker.enqueueImmediate(this)
        Timber.d("MonitoringService created")
    }

    /**
     * Register a callback that fires an immediate sync when network connectivity is
     * (re)established. This lets a child device recover automatically after any network
     * outage instead of waiting for the next periodic cycle (or a manual app restart).
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = cm
        // Stamp now so the onAvailable that fires at registration time (when already
        // online) is debounced - onStartCommand performs the startup sync separately.
        lastNetworkSyncTime = System.currentTimeMillis()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val now = System.currentTimeMillis()
                if (now - lastNetworkSyncTime < NETWORK_RESYNC_DEBOUNCE_MS) {
                    Timber.d("Network available - resync debounced")
                    return
                }
                lastNetworkSyncTime = now
                Timber.i("Network connectivity restored - triggering immediate resync")
                performInitialSync()
                triggerImmediateSync()
            }
        }
        networkCallback = callback
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
            Timber.d("Network connectivity callback registered")
        } catch (e: Exception) {
            Timber.e(e, "Failed to register network callback")
            networkCallback = null
        }
    }

    // Track if enforcement loop is already running to prevent multiple starts
    private var isEnforcementLoopRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("MonitoringService started")
        isRunning = true

        when (intent?.action) {
            ACTION_STOP -> {
                stopService()
                return START_NOT_STICKY
            }
            ACTION_SYNC_NOW -> {
                triggerImmediateSync()
            }
        }

        // Start as foreground service
        startForeground(Constants.NOTIFICATION_ID_MONITORING_SERVICE, createNotification())

        // Consent gate. A parental-control monitoring service must not run until the
        // parent has acknowledged the in-app monitoring disclosure (Google Play
        // Prominent Disclosure & Consent). Every start path (boot, restart, sync, app
        // open, manual) funnels through here, so this single check is the enforcement
        // point. startForeground is already called above to honour the
        // startForegroundService contract before we stop.
        if (!preferencesManager.shouldRunMonitoring) {
            Timber.w("MonitoringService start blocked: monitoring consent not granted")
            stopSelf()
            return START_NOT_STICKY
        }

        // Schedule periodic sync with WorkManager (battery efficient)
        // Only enqueue if not recently enqueued to prevent excessive job scheduling
        val now = System.currentTimeMillis()
        if (now - lastSyncWorkerEnqueueTime > SYNC_WORKER_ENQUEUE_INTERVAL_MS) {
            SyncWorker.enqueue(this)
            lastSyncWorkerEnqueueTime = now
        } else {
            Timber.d("Skipping SyncWorker enqueue - recently scheduled")
        }

        // Perform initial sync
        performInitialSync()

        // Keep the device heartbeat alive from the foreground service itself (Doze-proof),
        // independent of the WorkManager SyncWorker that Doze defers.
        startHeartbeatLoop()

        // Start enforcement loop (only for child devices)
        // Only start if not already running to prevent multiple loops
        val isParent = preferencesManager.isParent
        val userRole = preferencesManager.userRole
        Timber.d("MonitoringService: userRole=$userRole, isParent=$isParent, enforcementRunning=$isEnforcementLoopRunning")

        if (!isParent) {
            // Pairing gate: a child device only starts monitoring once the backend has
            // confirmed at least one linked parent (family link). Reads the CACHED flag
            // so the decision is deterministic at startup and works offline.
            if (preferencesManager.hasLinkedParent) {
                if (!isEnforcementLoopRunning) {
                    Timber.d("Child is paired -> starting enforcement + image monitoring")
                    startEnforcementLoop()

                    // Start image monitoring for sexting prevention (child devices only)
                    startImageMonitoring()
                } else {
                    Timber.d("Enforcement loop already running - skipping duplicate start")
                }
            } else {
                Timber.d("Child NOT yet paired -> monitoring gated; refreshing parent link")
                refreshParentLinkAndMaybeStartMonitoring()
            }
        } else {
            Timber.d("Parent device -> skipping enforcement loop")
        }

        return START_STICKY
    }

    /**
     * One-shot background check: if the backend confirms a linked parent, flip the
     * cached flag and start monitoring. Network failures leave everything unchanged
     * (a never-paired device simply stays gated until it can confirm a parent).
     */
    private fun refreshParentLinkAndMaybeStartMonitoring() {
        serviceScope.launch {
            val paired = familyRepository.refreshLinkedParentStatus()
            if (paired && !isEnforcementLoopRunning) {
                Timber.d("Parent link confirmed at runtime -> starting monitoring now")
                startEnforcementLoop()
                startImageMonitoring()
            }
        }
    }

    /**
     * Start image monitoring for sexting prevention.
     * Includes real-time FileObserver and periodic MediaStore scanning.
     */
    private fun startImageMonitoring() {
        try {
            // Start real-time file observer for camera/downloads
            mediaFileObserver.startWatching()
            Timber.i("Started MediaFileObserver for image monitoring")

            // Schedule periodic image scanning via WorkManager
            ImageScanWorker.enqueue(this)
            Timber.i("Scheduled periodic ImageScanWorker")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start image monitoring")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        // Cancel enforcement loop
        enforcementJob?.cancel()
        enforcementJob = null
        isEnforcementLoopRunning = false
        // Cancel heartbeat loop
        heartbeatJob?.cancel()
        heartbeatJob = null
        // Stop image monitoring
        try {
            mediaFileObserver.stopWatching()
            Timber.d("MediaFileObserver stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping MediaFileObserver")
        }
        // Unregister network connectivity callback
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering network callback")
        }
        networkCallback = null
        connectivityManager = null
        // Remove the lock overlay if up, so a dead service can't leak an orphaned window.
        // START_STICKY restarts the service, which re-evaluates and re-shows if still locked.
        try {
            lockOverlayController.hide()
        } catch (e: Exception) {
            Timber.w(e, "Error hiding lock overlay on destroy")
        }
        // Cancel all coroutines
        serviceJob?.cancel()
        serviceJob = null
        Timber.d("MonitoringService destroyed")
    }

    /**
     * Called when the user swipes the app away from recent tasks.
     * This is a potential tamper attempt - send alert and restart service.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Timber.w("MonitoringService: onTaskRemoved called")

        // Check if this is a child device that should be monitored
        val isChild = try {
            !preferencesManager.isParent && preferencesManager.isDeviceRegistered
        } catch (e: Exception) {
            false
        }
        if (!isChild) return

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // Rolling-window restart accounting (persisted: the cycle spans process deaths).
        var windowStart = prefs.getLong(KEY_RESTART_WINDOW_START, 0L)
        var count = prefs.getInt(KEY_RESTART_COUNT, 0)
        if (windowStart == 0L || now - windowStart > RESTART_WINDOW_MS) {
            windowStart = now
            count = 0
        }
        count++
        prefs.edit()
            .putLong(KEY_RESTART_WINDOW_START, windowStart)
            .putInt(KEY_RESTART_COUNT, count)
            .apply()

        val breakerOpen = count > MAX_RESTARTS_PER_WINDOW

        // Alert the parent only for a genuine, isolated stop - throttled to once per cooldown,
        // and suppressed entirely while the breaker is open (a restart loop is not a tamper
        // attempt, and 13 alerts in 2 minutes only trains parents to ignore them).
        val lastAlert = prefs.getLong(KEY_LAST_SERVICE_STOPPED_ALERT, 0L)
        if (!breakerOpen && now - lastAlert > SERVICE_STOPPED_ALERT_COOLDOWN_MS) {
            sendServiceStoppedAlert()
            prefs.edit().putLong(KEY_LAST_SERVICE_STOPPED_ALERT, now).apply()
        } else {
            Timber.w("onTaskRemoved: service-stopped alert suppressed (breakerOpen=$breakerOpen, count=$count/$MAX_RESTARTS_PER_WINDOW)")
        }

        // Restart, but stop hammering once the breaker opens. START_STICKY plus the periodic
        // ProtectionMonitorWorker still recover the service without the 1s alarm storm that
        // was pinning the CPU.
        if (breakerOpen) {
            Timber.e("onTaskRemoved: restart circuit-breaker OPEN ($count restarts in ${RESTART_WINDOW_MS / 1000}s) - not rescheduling immediate restart")
        } else {
            scheduleServiceRestart()
        }
    }

    /**
     * Send alert to parent when monitoring service is stopped unexpectedly.
     */
    private fun sendServiceStoppedAlert() {
        try {
            val workData = androidx.work.workDataOf(
                com.safeguard.parentalcontrol.receiver.TamperDetectionReceiver.KEY_TAMPER_TYPE to "service_stopped",
                com.safeguard.parentalcontrol.receiver.TamperDetectionReceiver.KEY_TAMPER_DETAILS to "Monitoring service was stopped (app swiped away)"
            )

            val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.safeguard.parentalcontrol.worker.TamperAlertWorker>()
                .setInputData(workData)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .addTag(com.safeguard.parentalcontrol.util.Constants.WORK_TAG_TAMPER_ALERT)
                .build()

            androidx.work.WorkManager.getInstance(this).enqueue(workRequest)
            Timber.i("Service stopped alert work enqueued")
        } catch (e: Exception) {
            Timber.e(e, "Failed to enqueue service stopped alert")
        }
    }

    /**
     * Schedule service restart using AlarmManager for reliability.
     */
    private fun scheduleServiceRestart() {
        try {
            val restartIntent = Intent(this, MonitoringService::class.java)
            val pendingIntent = android.app.PendingIntent.getService(
                this,
                1,
                restartIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager

            // Inexact, Doze-friendly restart. setExactAndAllowWhileIdle needs SCHEDULE_EXACT_ALARM
            // on Android 12+ and throws SecurityException without it - that failure (then the 1s
            // WorkManager fallback retrying) was part of what fed the restart loop. An inexact
            // alarm a few seconds out is sufficient for recovery and needs no special permission.
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + RESTART_DELAY_MS,
                pendingIntent
            )

            Timber.i("Monitoring service restart scheduled via AlarmManager (+${RESTART_DELAY_MS / 1000}s)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to schedule service restart")
            // Fallback: use WorkManager
            try {
                val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.safeguard.parentalcontrol.receiver.BootServiceStartWorker>()
                    .setInitialDelay(RESTART_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                androidx.work.WorkManager.getInstance(this).enqueue(workRequest)
                Timber.i("Service restart scheduled via WorkManager fallback")
            } catch (e2: Exception) {
                Timber.e(e2, "WorkManager fallback also failed")
            }
        }
    }

    /**
     * Periodically refresh the device heartbeat while the foreground service is alive.
     * Runs for both parent and child devices (both report a heartbeat). Foreground services
     * are exempt from Doze CPU restrictions, so this keeps last_sync fresh even when the
     * device is idle overnight - which the Doze-deferred SyncWorker could not guarantee.
     */
    private fun startHeartbeatLoop() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                try {
                    deviceRepository.syncDevice()
                    Timber.d("MonitoringService: heartbeat sent")
                } catch (e: Exception) {
                    Timber.w(e, "MonitoringService: heartbeat failed")
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /**
     * Start the enforcement loop that checks limits periodically
     */
    private fun startEnforcementLoop() {
        enforcementJob?.cancel()
        isEnforcementLoopRunning = true
        enforcementJob = serviceScope.launch {
            Timber.d("=== ENFORCEMENT LOOP STARTED for CHILD device ===")

            // Sync rules immediately when loop starts
            val deviceDbId = deviceRepository.getCurrentDeviceId()
            val deviceUuid = deviceRepository.getDeviceUniqueId()
            val deviceModel = android.os.Build.MODEL
            val deviceManufacturer = android.os.Build.MANUFACTURER

            // Log device info once at startup (compact format)
            Timber.d("ENFORCEMENT: deviceDbId=$deviceDbId, uuid=${deviceUuid.take(8)}..., model=$deviceManufacturer $deviceModel")

            if (deviceDbId > 0) {
                try {
                    Timber.d("ENFORCEMENT: Fetching rules for device $deviceDbId...")
                    val rules = screenTimeRulesRepository.getCachedRules(deviceDbId, forceRefresh = true)
                    if (rules != null) {
                        Timber.d("ENFORCEMENT: Rules loaded - isActive=${rules.isActive}, bedtime=${rules.bedtimeEnabled}(${rules.bedtimeStart}-${rules.bedtimeEnd}), dailyLimit=${rules.dailyLimit}, blockedApps=${rules.blockedApps}, studyTime=${rules.studyTimeEnabled}(${rules.studyTimeStart}-${rules.studyTimeEnd}), studyTimeAllowedApps=${rules.studyTimeAllowedApps}, isDeviceLocked=${rules.isDeviceLocked}")
                    } else {
                        Timber.w("ENFORCEMENT: No rules configured for device $deviceDbId")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "ENFORCEMENT: Failed to fetch rules for device $deviceDbId")
                }
            } else {
                Timber.e("ENFORCEMENT: Cannot sync rules - deviceDbId=$deviceDbId (not registered?)")
            }

            var loopCount = 0
            var lastRulesRefreshTime = System.currentTimeMillis()
            val rulesRefreshIntervalMs = 90 * 1000L // Refresh rules every 90s (ISSUE-023: cut parent->child propagation latency; no push yet)

            while (isActive) {
                try {
                    loopCount++

                    // Periodically refresh rules from server
                    val now = System.currentTimeMillis()
                    if (now - lastRulesRefreshTime > rulesRefreshIntervalMs && deviceDbId > 0) {
                        Timber.d("ENFORCEMENT: Periodic rules refresh (every 5 min)...")
                        try {
                            screenTimeRulesRepository.getCachedRules(deviceDbId, forceRefresh = true)
                            lastRulesRefreshTime = now
                        } catch (e: Exception) {
                            Timber.e(e, "ENFORCEMENT: Periodic rules refresh failed")
                        }
                    }

                    checkAndEnforceLimits()
                } catch (e: Exception) {
                    // Only log errors, not every iteration
                    Timber.e(e, "ENFORCEMENT: Error in check #$loopCount")
                }
                // Use adaptive interval: fast when restrictions active, slow when idle
                val interval = if (restrictionsCurrentlyActive) {
                    ENFORCEMENT_CHECK_INTERVAL_FAST_MS
                } else {
                    ENFORCEMENT_CHECK_INTERVAL_SLOW_MS
                }
                delay(interval)
            }
            isEnforcementLoopRunning = false
            Timber.d("=== ENFORCEMENT LOOP ENDED ===")
        }
    }

    /**
     * Check if a phone call is currently active
     */
    @Suppress("DEPRECATION")
    private fun isPhoneCallActive(): Boolean {
        return try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val callState = telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE
            val isActive = callState != TelephonyManager.CALL_STATE_IDLE
            if (isActive) {
                Timber.d("ENFORCEMENT: Phone call is active (callState=$callState)")
            }
            isActive
        } catch (e: Exception) {
            Timber.e(e, "Error checking phone call state")
            false
        }
    }

    /**
     * Check if an app is a phone/dialer app that should always be allowed
     */
    private fun isPhoneOrDialerApp(packageName: String): Boolean {
        return PHONE_DIALER_PACKAGES.contains(packageName)
    }

    /**
     * Check all screen time rules and enforce them
     */
    private suspend fun checkAndEnforceLimits() {
        // Clean up cache periodically to prevent memory bloat
        cleanupCacheIfNeeded()

        // Always allow phone calls - highest priority safety feature
        if (isPhoneCallActive()) {
            Timber.d("ENFORCEMENT: Phone call active - pausing enforcement")
            dismissLockScreenIfShowing()
            return
        }

        // Get foreground app for all checks
        val foregroundApp = getForegroundApp()
        Timber.d("ENFORCEMENT: Foreground app detected: $foregroundApp")

        // Check if foreground app is a phone/dialer app - always allow for emergency calls
        if (foregroundApp != null && isPhoneOrDialerApp(foregroundApp)) {
            Timber.d("ENFORCEMENT: Phone/dialer app in foreground ($foregroundApp) - allowing")
            dismissLockScreenIfShowing()
            return
        }

        val rules = screenTimeRulesRepository.cachedRules.value
        if (rules == null) {
            Timber.d("ENFORCEMENT: No rules cached - skipping enforcement")
            dismissLockScreenIfShowing()
            return
        }

        // Skip if rules not active
        if (!rules.isActive) {
            Timber.d("ENFORCEMENT: Rules not active (isActive=false) - skipping")
            restrictionsCurrentlyActive = false
            dismissLockScreenIfShowing()
            return
        }

        // Determine if any restrictions could be active (for adaptive interval)
        // This makes the enforcement loop check more frequently when restrictions might apply
        val hasBlockedApps = !rules.blockedApps.isNullOrEmpty()
        val hasAppLimits = !rules.appLimits.isNullOrEmpty()
        val hasDailyLimit = rules.dailyLimit != null && rules.dailyLimit > 0
        val currentTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        val couldBeInBedtime = rules.bedtimeEnabled
        val couldBeInStudyTime = rules.studyTimeEnabled

        restrictionsCurrentlyActive = rules.isDeviceLocked ||
            hasBlockedApps || hasAppLimits || hasDailyLimit ||
            couldBeInBedtime || couldBeInStudyTime

        // Check parent lock first (highest priority - parent can override everything)
        if (rules.isDeviceLocked) {
            Timber.d("ENFORCEMENT: DEVICE LOCKED BY PARENT - showing lock screen")
            val message = rules.deviceLockedMessage ?: getLocalizedString(R.string.notif_lock_parent_locked_msg)
            showLockScreen(
                LockScreenActivity.LOCK_TYPE_PARENT_LOCKED,
                message
            )
            return
        }

        // Check bedtime (second highest priority)
        // currentTime already defined above for adaptive interval check
        val isInBedtime = rules.isInBedtime(currentTime)

        Timber.d("ENFORCEMENT CHECK: bedtime=${rules.bedtimeEnabled}(${rules.bedtimeStart}-${rules.bedtimeEnd}), currentTime=$currentTime, isInBedtime=$isInBedtime")

        if (isInBedtime) {
            Timber.d("ENFORCEMENT: BEDTIME ACTIVE - showing lock screen")
            showLockScreen(
                LockScreenActivity.LOCK_TYPE_BEDTIME,
                getLocalizedString(R.string.notif_lock_bedtime_msg)
            )
            return
        }

        // Check study time (allows only educational apps)
        val isInStudyTime = rules.isInStudyTime(currentTime)
        val allowedApps = rules.studyTimeAllowedApps

        Timber.d("ENFORCEMENT CHECK: studyTime=${rules.studyTimeEnabled}(${rules.studyTimeStart}-${rules.studyTimeEnd}), isInStudyTime=$isInStudyTime, allowedApps=$allowedApps")

        if (isInStudyTime) {
            // During study time, check if current app is allowed
            if (foregroundApp != null && foregroundApp != packageName) {
                val isAllowed = rules.isAllowedDuringStudyTime(foregroundApp)
                Timber.d("ENFORCEMENT: Study time - checking app $foregroundApp, allowed=$isAllowed, allowedList=${allowedApps?.joinToString()}")
                if (!isAllowed) {
                    Timber.d("ENFORCEMENT: STUDY TIME - app $foregroundApp not allowed (allowedApps=$allowedApps)")
                    showLockScreen(
                        LockScreenActivity.LOCK_TYPE_STUDY_TIME,
                        getLocalizedString(R.string.notif_lock_study_time_msg),
                        foregroundApp
                    )
                    return
                }
            }
        }

        // Check daily limit
        val dailyLimit = rules.dailyLimit
        val todayScreenTime = screenTimeRepository.getTodayScreenTimeSeconds()
        val dailyLimitExceeded = dailyLimit != null && dailyLimit > 0 && todayScreenTime >= dailyLimit

        if (dailyLimitExceeded) {
            Timber.d("ENFORCEMENT: Daily limit exceeded: ${todayScreenTime}s >= ${dailyLimit}s")
            showLockScreen(
                LockScreenActivity.LOCK_TYPE_DAILY_LIMIT,
                getLocalizedString(R.string.notif_lock_daily_limit_msg)
            )
            return
        }

        // Check foreground app for blocked apps and per-app limits
        // (foregroundApp was already fetched at the start for phone/dialer check)
        if (foregroundApp == null) {
            Timber.d("ENFORCEMENT: foregroundApp is null - cannot check blocked apps/limits")
            // Don't dismiss lock screen if we can't detect foreground app - be conservative
            return
        }
        if (foregroundApp == packageName) {
            // Our own app is in foreground - allow
            dismissLockScreenIfShowing()
            return
        }

        // Check if app is blocked
        val blockedApps = rules.blockedApps ?: emptyList()
        val isBlocked = blockedApps.contains(foregroundApp)
        Timber.d("ENFORCEMENT: Checking blocked apps - foreground=$foregroundApp, blockedList=$blockedApps, isBlocked=$isBlocked")

        if (isBlocked) {
            Timber.d("ENFORCEMENT: BLOCKED APP DETECTED: $foregroundApp")
            showLockScreen(
                LockScreenActivity.LOCK_TYPE_APP_BLOCKED,
                getLocalizedString(R.string.notif_lock_app_blocked_msg),
                getAppName(foregroundApp) ?: foregroundApp
            )
            return
        }

        // Check per-app limit
        val appLimit = rules.appLimits?.get(foregroundApp)
        if (appLimit != null && appLimit > 0) {
            val appUsageToday = getAppUsageToday(foregroundApp)
            if (appUsageToday >= appLimit) {
                Timber.d("ENFORCEMENT: App limit exceeded: $foregroundApp (${appUsageToday}s >= ${appLimit}s)")
                showLockScreen(
                    LockScreenActivity.LOCK_TYPE_APP_LIMIT,
                    getLocalizedString(R.string.notif_lock_app_limit_msg),
                    getAppName(foregroundApp) ?: foregroundApp
                )
                return
            }
        }

        // No restrictions - dismiss lock screen if showing
        dismissLockScreenIfShowing()
    }

    /**
     * Clean up old cache entries to prevent memory bloat
     * Called periodically from checkAndEnforceLimits
     */
    private fun cleanupCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastCacheCleanupTime < CACHE_CLEANUP_INTERVAL_MS) {
            return // Not time to clean yet
        }
        lastCacheCleanupTime = now

        // Remove expired entries from app usage cache
        val expiredKeys = appUsageCache.filter { (_, value) ->
            now - value.first > APP_USAGE_CACHE_DURATION_MS * 2 // Remove entries older than 2x cache duration
        }.keys

        expiredKeys.forEach { appUsageCache.remove(it) }

        // If cache is still too large, remove oldest entries
        if (appUsageCache.size > APP_USAGE_CACHE_MAX_SIZE) {
            val sortedEntries = appUsageCache.entries.sortedBy { it.value.first }
            val toRemove = sortedEntries.take(appUsageCache.size - APP_USAGE_CACHE_MAX_SIZE / 2)
            toRemove.forEach { appUsageCache.remove(it.key) }
        }

        if (expiredKeys.isNotEmpty()) {
            Timber.d("Cache cleanup: removed ${expiredKeys.size} expired entries, cache size=${appUsageCache.size}")
        }
    }

    /**
     * Get the currently running foreground app package name
     *
     * Uses multiple detection methods for reliability:
     * 1. queryEvents for recent ACTIVITY_RESUMED events
     * 2. queryUsageStats as fallback for apps with recent usage
     *
     * Uses caching to avoid querying UsageStatsManager too frequently.
     */
    private fun getForegroundApp(): String? {
        val now = System.currentTimeMillis()

        // Check cache first - use shorter cache when restrictions are active
        val cacheDuration = if (restrictionsCurrentlyActive) {
            FOREGROUND_APP_CACHE_FAST_MS
        } else {
            FOREGROUND_APP_CACHE_SLOW_MS
        }
        if (cachedForegroundApp != null && now - cachedForegroundAppTime < cacheDuration) {
            return cachedForegroundApp
        }

        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usageStatsManager == null) {
            Timber.w("getForegroundApp: UsageStatsManager is null")
            return null
        }

        var detectedApp: String? = null

        // Method 1: Try queryEvents first (most accurate for recent activity)
        detectedApp = getForegroundAppViaEvents(usageStatsManager, now)

        // Method 2: Fallback to queryUsageStats if events didn't find anything
        if (detectedApp == null) {
            detectedApp = getForegroundAppViaUsageStats(usageStatsManager, now)
        }

        // Update cache (even if null, to prevent hammering the API)
        cachedForegroundApp = detectedApp
        cachedForegroundAppTime = now

        if (detectedApp == null) {
            Timber.w("getForegroundApp: Could not detect foreground app via any method")
        }

        return detectedApp
    }

    /**
     * Get foreground app using queryEvents - checks for ACTIVITY_RESUMED events
     */
    private fun getForegroundAppViaEvents(usageStatsManager: UsageStatsManager, now: Long): String? {
        val endTime = now
        // Extend window to 5 minutes to catch apps that have been open for a while
        val startTime = endTime - 300000

        return try {
            val events = usageStatsManager.queryEvents(startTime, endTime)
            val event = android.app.usage.UsageEvents.Event()

            var lastForegroundApp: String? = null
            var lastForegroundTime = 0L
            var lastPausedApp: String? = null
            var lastPausedTime = 0L

            // Find the most recent foreground/paused events
            while (events.hasNextEvent()) {
                events.getNextEvent(event)

                when (event.eventType) {
                    android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (event.timeStamp > lastForegroundTime) {
                            lastForegroundTime = event.timeStamp
                            lastForegroundApp = event.packageName
                        }
                    }
                    android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED -> {
                        if (event.timeStamp > lastPausedTime) {
                            lastPausedTime = event.timeStamp
                            lastPausedApp = event.packageName
                        }
                    }
                }
            }

            // If the last resumed app was also the last paused, it's no longer in foreground
            // In that case, return null and let fallback method handle it
            val result = if (lastForegroundApp != null && lastForegroundTime > lastPausedTime) {
                lastForegroundApp
            } else if (lastForegroundApp != null && lastPausedApp != lastForegroundApp) {
                // Last resumed app is different from last paused - it's still in foreground
                lastForegroundApp
            } else {
                null
            }

            if (result != null) {
                Timber.d("getForegroundApp: $result (via queryEvents)")
            }
            result
        } catch (e: Exception) {
            Timber.e(e, "Error getting foreground app via events")
            null
        }
    }

    /**
     * Get foreground app using queryUsageStats - fallback method
     * Finds the app with the most recent lastTimeUsed within a short window
     */
    private fun getForegroundAppViaUsageStats(usageStatsManager: UsageStatsManager, now: Long): String? {
        return try {
            // Query last 10 seconds for very recent usage
            val startTime = now - 10000
            val usageStatsList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                startTime,
                now
            )

            if (usageStatsList.isNullOrEmpty()) {
                return null
            }

            // Find the app with the most recent lastTimeUsed
            val recentApp = usageStatsList
                .filter { it.packageName != packageName } // Exclude our own app
                .filter { it.lastTimeUsed > startTime } // Must have been used in the window
                .maxByOrNull { it.lastTimeUsed }

            val result = recentApp?.packageName

            if (result != null) {
                Timber.d("getForegroundApp: $result (via queryUsageStats, lastUsed=${recentApp.lastTimeUsed})")
            }
            result
        } catch (e: Exception) {
            Timber.e(e, "Error getting foreground app via usage stats")
            null
        }
    }

    /**
     * Get usage time for a specific app today using UsageEvents for real-time accuracy
     *
     * Uses caching to avoid querying UsageStatsManager too frequently.
     * Cache is valid for APP_USAGE_CACHE_DURATION_MS (30 seconds).
     */
    private fun getAppUsageToday(packageName: String): Int {
        val now = System.currentTimeMillis()

        // Check cache first
        appUsageCache[packageName]?.let { (timestamp, cachedUsage) ->
            if (now - timestamp < APP_USAGE_CACHE_DURATION_MS) {
                // Cache is still valid - but add elapsed time since cache was created
                // if the app is currently in foreground
                val foregroundApp = cachedForegroundApp
                val elapsedSeconds = if (foregroundApp == packageName) {
                    ((now - timestamp) / 1000).toInt()
                } else {
                    0
                }
                return cachedUsage + elapsedSeconds
            }
        }

        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0

        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis
        val endTime = now

        return try {
            // Use queryEvents for real-time usage calculation
            val events = usageStatsManager.queryEvents(startTime, endTime)
            val event = android.app.usage.UsageEvents.Event()

            var totalTimeMs = 0L
            var lastResumeTime = 0L

            while (events.hasNextEvent()) {
                events.getNextEvent(event)

                if (event.packageName != packageName) continue

                when (event.eventType) {
                    android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                        lastResumeTime = event.timeStamp
                    }
                    android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED -> {
                        if (lastResumeTime > 0) {
                            totalTimeMs += event.timeStamp - lastResumeTime
                            lastResumeTime = 0
                        }
                    }
                }
            }

            // If app is currently in foreground (no PAUSED event yet), add time until now
            if (lastResumeTime > 0) {
                totalTimeMs += endTime - lastResumeTime
            }

            val totalTimeSeconds = (totalTimeMs / 1000).toInt()

            // Update cache
            appUsageCache[packageName] = Pair(now, totalTimeSeconds)

            Timber.d("getAppUsageToday: $packageName = ${totalTimeSeconds}s (via queryEvents)")
            totalTimeSeconds
        } catch (e: Exception) {
            Timber.e(e, "Error getting app usage via events, falling back to stats")
            // Fallback to stats-based query
            getFallbackAppUsage(usageStatsManager, packageName, startTime, endTime)
        }
    }

    /**
     * Fallback to UsageStats if queryEvents fails
     */
    private fun getFallbackAppUsage(
        usageStatsManager: UsageStatsManager,
        packageName: String,
        startTime: Long,
        endTime: Long
    ): Int {
        val usageStats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        return usageStats
            ?.find { it.packageName == packageName }
            ?.totalTimeInForeground
            ?.let { (it / 1000).toInt() }
            ?: 0
    }

    /**
     * Show the lock screen activity using full-screen intent notification
     *
     * On Android 10+ (API 29), starting activities from background is restricted.
     * Using a full-screen intent notification is the reliable approach for parental control apps.
     * This method:
     * 1. Creates a high-priority notification with full-screen intent
     * 2. On Android 10+, the system will show the lock screen activity
     * 3. Uses time-based throttling to prevent spam
     * 4. Skips re-showing if lock screen is already active for the same reason
     */
    private fun showLockScreen(lockType: String, message: String, appName: String? = null) {
        Timber.d("showLockScreen called: type=$lockType, message=$message, app=$appName")

        // IMPORTANT: Don't show lock screen if screen is off - let the user sleep!
        // The enforcement loop will show it again when screen turns on.
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenOn = powerManager?.isInteractive ?: true
        if (!isScreenOn) {
            Timber.d("showLockScreen: Screen is OFF - not showing lock screen to save battery")
            return
        }

        // We prefer a TYPE_APPLICATION_OVERLAY window over an Activity: an Activity is a
        // fullscreen *task* and Android renders PiP / freeform / pop-up windows above the
        // fullscreen task stack, so a child can float YouTube on top of an Activity lock.
        // The overlay sits in the overlay layer band, above those, and actually covers them.
        val canDrawOverlays = Settings.canDrawOverlays(this)
        val apiLevel = Build.VERSION.SDK_INT

        // If the lock is already showing for this exact reason, don't re-show.
        val alreadyShowing = if (canDrawOverlays) {
            lockOverlayController.isShowingForType(lockType)
        } else {
            LockScreenActivity.isShowingForType(lockType)
        }
        if (alreadyShowing) {
            Timber.d("showLockScreen: already showing for $lockType - skipping")
            return
        }

        val now = System.currentTimeMillis()
        val timeSinceLastShow = now - lastLockScreenShowTime

        // Re-show throttle - keeps the lock coming back if the child tries to bypass it,
        // without thrashing.
        if (timeSinceLastShow < LOCK_SCREEN_RESHOW_INTERVAL_MS) {
            Timber.d("showLockScreen: throttled (timeSinceLastShow=${timeSinceLastShow}ms < ${LOCK_SCREEN_RESHOW_INTERVAL_MS}ms)")
            return // Too soon to re-show
        }

        lastLockScreenShowTime = now
        Timber.d("showLockScreen: API level=$apiLevel, canDrawOverlays=$canDrawOverlays")

        if (canDrawOverlays) {
            // Overlay path - covers PiP / freeform / pop-up windows an Activity cannot.
            Timber.d("showLockScreen: Using system-overlay lock")
            lockOverlayController.show(lockType, message, appName)
        } else if (apiLevel >= Build.VERSION_CODES.Q) {
            // No overlay permission: fall back to the Activity via a full-screen-intent notification.
            Timber.w("showLockScreen: No overlay permission! Using notification fallback. Grant 'Display over other apps' permission.")
            LockScreenActivity.resetDismiss()
            val intent = LockScreenActivity.createIntent(this, lockType, message, appName)
            showLockScreenViaNotification(intent, lockType, message)
        } else {
            // Pre-Q without overlay permission: direct activity start works.
            LockScreenActivity.resetDismiss()
            startActivity(LockScreenActivity.createIntent(this, lockType, message, appName))
        }

        Timber.d("Lock screen shown: $lockType (timeSinceLastShow=${timeSinceLastShow}ms)")
    }

    /**
     * Show lock screen via full-screen intent notification (Android 10+)
     *
     * Full-screen intents are designed for time-critical alerts like alarms and calls.
     * Parental control lock screens qualify as they need immediate user attention.
     */
    private fun showLockScreenViaNotification(intent: Intent, lockType: String, message: String) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            LOCK_SCREEN_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(this, SafeGuardApplication.CHANNEL_LOCK_SCREEN)
            .setContentTitle(getLockScreenTitle(lockType))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM) // Treat as alarm for maximum priority
            .setFullScreenIntent(pendingIntent, true) // This triggers the activity on Android 10+
            .setAutoCancel(false)
            .setOngoing(true)
            .setGroup("safeguard_lock_screen")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()

        notificationManager.notify(NOTIFICATION_ID_LOCK_SCREEN, notification)
        Timber.d("Lock screen notification shown with full-screen intent")
    }

    /**
     * Get appropriate title for lock screen notification
     */
    private fun getLockScreenTitle(lockType: String): String {
        return when (lockType) {
            LockScreenActivity.LOCK_TYPE_DAILY_LIMIT -> getLocalizedString(R.string.notif_lock_daily_limit_title)
            LockScreenActivity.LOCK_TYPE_BEDTIME -> getLocalizedString(R.string.notif_lock_bedtime_title)
            LockScreenActivity.LOCK_TYPE_APP_BLOCKED -> getLocalizedString(R.string.notif_lock_app_blocked_title)
            LockScreenActivity.LOCK_TYPE_APP_LIMIT -> getLocalizedString(R.string.notif_lock_app_limit_title)
            LockScreenActivity.LOCK_TYPE_STUDY_TIME -> getLocalizedString(R.string.notif_lock_study_time_title)
            LockScreenActivity.LOCK_TYPE_PARENT_LOCKED -> getLocalizedString(R.string.notif_lock_parent_locked_title)
            else -> getLocalizedString(R.string.notif_lock_default_title)
        }
    }

    /**
     * Dismiss lock screen if it's currently showing
     * Also cancels the lock screen notification on Android 10+
     */
    private fun dismissLockScreenIfShowing() {
        if (lastLockScreenShowTime > 0) {
            lastLockScreenShowTime = 0
            // Remove whichever surface is up. Each is a no-op if it isn't the active one.
            lockOverlayController.hide()
            LockScreenActivity.allowDismiss()

            // Cancel the lock screen notification on Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID_LOCK_SCREEN)
            }

            Timber.d("Lock screen dismissed")
        }
    }

    /**
     * Perform initial sync when service starts
     * Only CHILD devices sync screen time and app usage
     * Parent devices only sync device heartbeat
     */
    private fun performInitialSync() {
        serviceScope.launch {
            try {
                val isParent = preferencesManager.isParent
                Timber.d("Performing initial sync (isParent=$isParent)")

                // Only child devices should sync their screen time and app usage
                if (!isParent) {
                    screenTimeRepository.syncScreenTimeFromDevice()
                    screenTimeRepository.syncAppUsageFromDevice()

                    // Sync screen time rules for enforcement (CHILD ONLY)
                    val deviceId = deviceRepository.getCurrentDeviceId()
                    if (deviceId > 0) {
                        screenTimeRulesRepository.getCachedRules(deviceId, forceRefresh = true)
                        Timber.d("Screen time rules synced for enforcement")
                    }
                } else {
                    Timber.d("Skipping screen time/app usage sync for PARENT device")
                }

                // Device heartbeat sync for both parent and child
                deviceRepository.syncDevice()

                Timber.d("Initial sync completed")
            } catch (e: Exception) {
                Timber.e(e, "Initial sync failed")
            }
        }
    }

    /**
     * Trigger immediate sync via WorkManager
     */
    private fun triggerImmediateSync() {
        SyncWorker.enqueueImmediate(this)
        Timber.d("Immediate sync triggered")
    }

    /**
     * Stop the service and cancel WorkManager jobs
     */
    private fun stopService() {
        SyncWorker.cancel(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Timber.d("MonitoringService stopped")
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action to sync now
        val syncIntent = Intent(this, MonitoringService::class.java).apply {
            action = ACTION_SYNC_NOW
        }
        val syncPendingIntent = PendingIntent.getService(
            this, 1, syncIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SafeGuardApplication.CHANNEL_MONITORING_SERVICE)
            .setContentTitle(getLocalizedString(R.string.notif_monitoring_title))
            .setContentText(getLocalizedString(R.string.notif_monitoring_text))
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setGroup("safeguard_monitoring")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setStyle(NotificationCompat.BigTextStyle().bigText(getLocalizedString(R.string.notif_monitoring_bigtext)))
            .addAction(R.drawable.ic_shield, getLocalizedString(R.string.notif_monitoring_sync_now), syncPendingIntent)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.safeguard.parentalcontrol.STOP_MONITORING"
        const val ACTION_SYNC_NOW = "com.safeguard.parentalcontrol.SYNC_NOW"
        private const val NOTIFICATION_ID_LOCK_SCREEN = 2001
        private const val LOCK_SCREEN_REQUEST_CODE = 3001

        /**
         * Tracks whether the MonitoringService is currently running.
         * Used by ServiceRestartReceiver to check if service needs to be restarted.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun getStartIntent(context: Context): Intent {
            return Intent(context, MonitoringService::class.java)
        }

        fun getStopIntent(context: Context): Intent {
            return Intent(context, MonitoringService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
