package com.safeguard.parentalcontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.safeguard.parentalcontrol.BuildConfig
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.ml.ContentClassifier
import com.safeguard.parentalcontrol.util.FlaggedTextStore
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Accessibility service for monitoring text content across apps
 * Used for detecting inappropriate text in messaging apps, browsers, etc.
 *
 * Optimized for battery efficiency:
 * - Only listens to TYPE_VIEW_TEXT_CHANGED (not window content changes)
 * - Package-level debouncing to reduce processing
 * - Rate limiting to prevent excessive CPU usage
 * - Proper CoroutineScope lifecycle management
 *
 * Memory leak prevention:
 * - Uses a stable CoroutineScope that's properly cancelled on destroy
 * - Recycles AccessibilityNodeInfo objects to prevent native memory leaks
 * - Extracts data from AccessibilityEvent before async processing
 * - Cleans up all maps and pending jobs on service destruction
 */
/**
 * Hilt EntryPoint for manual injection fallback.
 * AccessibilityService sometimes has issues with Hilt's automatic injection,
 * so we provide a manual injection path as backup.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TextMonitoringAccessibilityServiceEntryPoint {
    fun alertRepository(): AlertRepository
    fun contentClassifier(): ContentClassifier
    fun preferencesManager(): PreferencesManager
    fun flaggedTextStore(): FlaggedTextStore
}

@AndroidEntryPoint
class TextMonitoringAccessibilityService : AccessibilityService() {

    companion object {
        // Static flag to track if service has ever been created (survives service restarts)
        @Volatile
        var serviceEverCreated = false
            private set

        init {
            // This logs when the class is LOADED (before any instance is created)
            // Using Log.d instead of Timber in case Timber isn't initialized yet
            Timber.d("CLASS LOADED - TextMonitoringAccessibilityService class initialized")
        }
    }

    @Inject
    lateinit var alertRepository: AlertRepository

    @Inject
    lateinit var contentClassifier: ContentClassifier

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var flaggedTextStore: FlaggedTextStore

    // Stable CoroutineScope - created once, cancelled on destroy
    private lateinit var serviceJob: Job
    private lateinit var serviceScope: CoroutineScope

    // Package-level debouncing with pending analysis jobs
    private val pendingAnalysis = ConcurrentHashMap<String, Job>()
    private val packageLastEventTime = ConcurrentHashMap<String, Long>()
    private val packageDebounceMs = 2000L // 2 seconds between same-package events

    // Rate limiting
    private var eventCount = 0
    private var lastEventCountReset = System.currentTimeMillis()
    private val maxEventsPerSecond = 10

    // Flag to track if service is properly initialized
    private var isInitialized = false

    // Home/launcher packages, resolved from the HOME intent so ANY OEM launcher is
    // skipped (not just the hardcoded ones). Lazy + cached — the default launcher
    // rarely changes during a session.
    private val launcherPackages: Set<String> by lazy {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(homeIntent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        } catch (e: Exception) {
            Timber.w("Could not resolve launcher packages: ${e.message}")
            emptySet()
        }
    }

    override fun onCreate() {
        // Use Log.d first in case Timber isn't initialized yet
        Timber.d("onCreate() CALLED - Service instance being created")
        serviceEverCreated = true

        super.onCreate()
        Timber.d("onCreate() - super.onCreate() completed")

        try {
            // Initialize coroutine scope with SupervisorJob for proper lifecycle management
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
            Timber.d("onCreate() - CoroutineScope initialized")

            // Check if Hilt injection was successful
            isInitialized = try {
                val repoInit = ::alertRepository.isInitialized
                val classifierInit = ::contentClassifier.isInitialized
                Timber.d("onCreate() - Hilt check: alertRepository=$repoInit, contentClassifier=$classifierInit")
                repoInit && classifierInit
            } catch (e: Exception) {
                Timber.w("onCreate() - Hilt isInitialized check failed: ${e.message}")
                false
            }

            // If automatic Hilt injection failed, try manual injection via EntryPoint
            if (!isInitialized) {
                Timber.w("onCreate() - Automatic Hilt injection failed, attempting manual injection")
                Timber.w("TextMonitoringAccessibilityService: Automatic Hilt injection failed, attempting manual injection")
                try {
                    val entryPoint = EntryPointAccessors.fromApplication(
                        applicationContext,
                        TextMonitoringAccessibilityServiceEntryPoint::class.java
                    )
                    alertRepository = entryPoint.alertRepository()
                    contentClassifier = entryPoint.contentClassifier()
                    preferencesManager = entryPoint.preferencesManager()
                    flaggedTextStore = entryPoint.flaggedTextStore()
                    isInitialized = true
                    Timber.d("onCreate() - Manual EntryPoint injection successful")
                    Timber.d("TextMonitoringAccessibilityService: Manual injection successful")
                } catch (e: Exception) {
                    Timber.e(e, "onCreate() - Manual injection FAILED: ${e.message}")
                    Timber.e(e, "TextMonitoringAccessibilityService: Manual injection also failed")
                    isInitialized = false
                }
            }

            if (!isInitialized) {
                Timber.e("onCreate() - ALL INJECTION FAILED - service will NOT function")
                Timber.e("TextMonitoringAccessibilityService: All injection attempts failed - service will not function")
            } else {
                Timber.i("onCreate() - SUCCESS - Service created and initialized")
                Timber.d("TextMonitoringAccessibilityService created and initialized successfully")
            }
        } catch (e: Exception) {
            Timber.e(e, "onCreate() - EXCEPTION during initialization: ${e.message}")
            Timber.e(e, "TextMonitoringAccessibilityService: Error during onCreate")
            isInitialized = false
        }
    }

    override fun onServiceConnected() {
        Timber.i("onServiceConnected() CALLED - Android has connected to our service!")
        super.onServiceConnected()
        Timber.d("TextMonitoringAccessibilityService connected")

        try {
            // Configure the service - listen to text changes AND window state changes
            // (window state changes help trigger initial connection on some devices)
            serviceInfo = serviceInfo?.apply {
                // Listen to both text changes and window content changes for better reliability
                eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                notificationTimeout = 200
            }
            Timber.d("onServiceConnected() - serviceInfo configured: eventTypes=${serviceInfo?.eventTypes}")

            // Re-check initialization in case dependencies weren't ready during onCreate
            if (!isInitialized) {
                Timber.w("onServiceConnected() - Not initialized in onCreate, retrying...")
                Timber.w("TextMonitoringAccessibilityService: Not initialized during onCreate, retrying in onServiceConnected")
                try {
                    val entryPoint = EntryPointAccessors.fromApplication(
                        applicationContext,
                        TextMonitoringAccessibilityServiceEntryPoint::class.java
                    )
                    alertRepository = entryPoint.alertRepository()
                    contentClassifier = entryPoint.contentClassifier()
                    preferencesManager = entryPoint.preferencesManager()
                    flaggedTextStore = entryPoint.flaggedTextStore()
                    isInitialized = true
                    Timber.d("onServiceConnected() - Late initialization SUCCESS")
                    Timber.d("TextMonitoringAccessibilityService: Late initialization successful in onServiceConnected")
                } catch (e: Exception) {
                    Timber.e(e, "onServiceConnected() - Late initialization FAILED: ${e.message}")
                    Timber.e(e, "TextMonitoringAccessibilityService: Late initialization failed")
                }
            }

            Timber.i("onServiceConnected() COMPLETE - isInitialized=$isInitialized, ready for events")
            Timber.d("TextMonitoringAccessibilityService: isInitialized=$isInitialized, ready for text monitoring")
        } catch (e: Exception) {
            Timber.e(e, "onServiceConnected() EXCEPTION: ${e.message}")
            Timber.e(e, "TextMonitoringAccessibilityService: Error in onServiceConnected")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        val eventTypeName = when (eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TEXT_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT"
            else -> "OTHER($eventType)"
        }

        val now = System.currentTimeMillis()

        // Skip if service isn't properly initialized (Hilt injection failed)
        if (!isInitialized) {
            Timber.w("onAccessibilityEvent() - Not initialized, attempting on-demand injection")
            // Try to re-initialize via EntryPoint (last resort)
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    applicationContext,
                    TextMonitoringAccessibilityServiceEntryPoint::class.java
                )
                alertRepository = entryPoint.alertRepository()
                contentClassifier = entryPoint.contentClassifier()
                preferencesManager = entryPoint.preferencesManager()
                flaggedTextStore = entryPoint.flaggedTextStore()
                isInitialized = true
                Timber.d("onAccessibilityEvent() - On-demand initialization SUCCESS")
                Timber.d("TextMonitoringAccessibilityService: On-demand initialization successful")
            } catch (e: Exception) {
                // Still not initialized - silently skip this event
                Timber.e("onAccessibilityEvent() - On-demand initialization FAILED: ${e.message}")
                return
            }
        }

        // Consent gate: never read or classify on-screen text until the parent has
        // accepted the in-app monitoring disclosure (Play Prominent Disclosure &
        // Consent). The setup flow collects consent before this service can be enabled;
        // this is defense-in-depth. Guarded so a failed injection can't crash here.
        if (::preferencesManager.isInitialized && !preferencesManager.monitoringConsentGranted) {
            return
        }

        // Rate limiting - prevent excessive CPU usage
        if (now - lastEventCountReset > 1000) {
            eventCount = 0
            lastEventCountReset = now
        }
        if (++eventCount > maxEventsPerSecond) {
            return // Drop event
        }

        // Process both TEXT_CHANGED and WINDOW_CONTENT_CHANGED events
        // Modern apps (Chrome, etc.) often don't fire TEXT_CHANGED reliably
        // WINDOW_STATE_CHANGED is too noisy and doesn't contain useful text
        val shouldProcess = eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        if (!shouldProcess) {
            return
        }

        val packageName = event.packageName?.toString() ?: return

        // Skip system packages and our own app
        if (shouldSkipPackage(packageName)) return

        // Package-level debouncing
        val lastEventTime = packageLastEventTime[packageName] ?: 0
        if (now - lastEventTime < packageDebounceMs) {
            return // Too soon since last event from this package
        }
        packageLastEventTime[packageName] = now

        // Cancel any pending analysis for this package
        pendingAnalysis[packageName]?.cancel()

        // CRITICAL: Extract text synchronously BEFORE launching coroutine
        // AccessibilityEvent objects are recycled by the system and become invalid
        // after onAccessibilityEvent returns, so we must extract data immediately
        val extractedText = try {
            val source = event.source
            if (source != null) {
                try {
                    extractText(source)
                } finally {
                    // Always recycle the source node to prevent native memory leaks
                    @Suppress("DEPRECATION")
                    source.recycle()
                }
            } else {
                // SECURITY: Check event-level password flag before using fallback text
                if (event.isPassword) {
                    ""
                } else {
                    event.text?.joinToString(" ") ?: ""
                }
            }
        } catch (e: Exception) {
            Timber.v("Error extracting text from event: ${e.message}")
            ""
        }

        // Skip if text is too short or blank
        // Minimum 3 characters to catch single words like "sex", "porn", etc.
        if (extractedText.isBlank() || extractedText.length < 3) return

        // Log metadata only — NEVER log captured text content to Logcat
        if (BuildConfig.DEBUG) {
            Timber.d("Extracted text from $packageName ($eventTypeName): length=${extractedText.length}")
        }

        // Schedule debounced analysis with already-extracted text
        pendingAnalysis[packageName] = serviceScope.launch {
            delay(500) // Additional short delay for batching rapid changes
            handleTextEvent(extractedText, packageName)
        }
    }

    private suspend fun handleTextEvent(text: String, packageName: String) {
        try {
            if (BuildConfig.DEBUG) {
                Timber.d("handleTextEvent: Analyzing text from $packageName (${text.length} chars)")
            }
            Timber.d("Analyzing text from %s: length=%d", packageName, text.length)
            analyzeText(packageName, text)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Timber.e("handleTextEvent: Error - ${e.message}")
                Timber.e(e, "Error handling text event")
            }
        }
    }

    private fun extractText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""

        // SECURITY: Skip password fields entirely
        if (isPasswordField(node)) {
            Timber.v("Skipping password field")
            return ""
        }

        val text = StringBuilder()

        // Extract text from current node
        node.text?.let { text.append(it).append(" ") }
        node.contentDescription?.let { text.append(it).append(" ") }

        // Recursively extract from children (limited depth and count)
        extractChildText(node, text, depth = 0, maxDepth = 3, maxLength = 1000)

        return text.toString().trim()
    }

    /**
     * Check if a node is a password field or other sensitive input.
     * We must NEVER read text from these fields.
     */
    private fun isPasswordField(node: AccessibilityNodeInfo): Boolean {
        // Check isPassword property (most reliable)
        if (node.isPassword) return true

        // Check view ID for password-related fields
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (viewId.contains("password") ||
            viewId.contains("passwd") ||
            viewId.contains("secret") ||
            viewId.contains("credit_card") ||
            viewId.contains("creditcard") ||
            viewId.contains("card_number") ||
            viewId.contains("cardnumber") ||
            viewId.contains("cvv") ||
            viewId.contains("cvc") ||
            viewId.contains("ssn") ||
            viewId.contains("social_security")) {
            return true
        }

        // Word-boundary-aware check for "pin" to avoid matching spinner, opinion, etc.
        if (Regex("(^|[^a-z])pin([^a-z]|$)").containsMatchIn(viewId)) {
            return true
        }

        // Check content description and hint
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val hintText = node.hintText?.toString()?.lowercase() ?: ""

        val sensitiveKeywords = listOf("password", "credit card", "cvv", "security code", "ssn")
        for (keyword in sensitiveKeywords) {
            if (contentDesc.contains(keyword) || hintText.contains(keyword)) {
                return true
            }
        }

        // Word-boundary check for "pin" in content description and hint text
        val pinPattern = Regex("\\bpin\\b", RegexOption.IGNORE_CASE)
        if (pinPattern.containsMatchIn(contentDesc) || pinPattern.containsMatchIn(hintText)) {
            return true
        }

        return false
    }

    private fun extractChildText(
        node: AccessibilityNodeInfo,
        text: StringBuilder,
        depth: Int,
        maxDepth: Int,
        maxLength: Int
    ) {
        // Stop if max depth reached or text is long enough
        if (depth >= maxDepth || text.length > maxLength) return

        // Limit number of children to process
        val childCount = minOf(node.childCount, 10)

        for (i in 0 until childCount) {
            try {
                val child = node.getChild(i) ?: continue
                try {
                    // SECURITY: Skip password/sensitive fields in child nodes
                    if (isPasswordField(child)) continue
                    child.text?.let { text.append(it).append(" ") }
                    extractChildText(child, text, depth + 1, maxDepth, maxLength)
                } finally {
                    // Always recycle to prevent memory leaks
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            } catch (e: Exception) {
                // Node might be recycled or unavailable
                Timber.v("Error extracting child text: ${e.message}")
            }
        }
    }

    private suspend fun analyzeText(packageName: String, text: String) {
        try {
            Timber.d("analyzeText: Calling contentClassifier.analyzeText()")
            val result = contentClassifier.analyzeText(text, packageName)
            Timber.d("analyzeText: Result - isFlagged=${result.isFlagged}, categories=${result.categories}, confidence=${result.confidence}")

            if (result.isFlagged) {
                val appName = getAppName(packageName)
                Timber.w("FLAGGED! Inappropriate text detected in $packageName")
                Timber.w("FLAGGED: Inappropriate text in $packageName: categories=${result.categories}, confidence=${result.confidence}, reason=${result.reason}")

                // Persist the flagged phrase on-device ONLY (never transmitted) so a parent
                // can review it behind the PIN. Done independently of the alert below so every
                // flagged phrase is reviewable even when the alert is deduped/cooled-down.
                if (::flaggedTextStore.isInitialized) {
                    flaggedTextStore.add(
                        phrase = text,
                        appName = appName ?: packageName,
                        category = result.categories.firstOrNull() ?: "unknown"
                    )
                }

                // Send alert with full context (categories, confidence, text for deduplication)
                // Note: The actual text is only used for hashing (deduplication), never stored or transmitted
                val alertResult = alertRepository.createInappropriateTextAlert(
                    appPackage = packageName,
                    appName = appName,
                    reason = result.reason ?: result.categories.joinToString(", "),
                    categories = result.categories,
                    confidence = result.confidence,
                    textForDedup = text, // Used for hash-based deduplication only
                    severityLabel = result.severity // gating-computed severity (Stage-2); null => derived from category
                )

                alertResult.onSuccess {
                    Timber.i("SUCCESS: Text alert created for $packageName: id=${it.id}, severity=${it.severity}")
                }.onError { message, _ ->
                    // Log why alert wasn't created (cooldown, deduplication, daily limit, etc.)
                    Timber.w("SKIPPED: Alert not created for $packageName: $message")
                }
            } else {
                Timber.d("SAFE: Text from $packageName passed analysis")
            }
        } catch (e: Exception) {
            Timber.e(e, "ERROR: Failed to analyze text from $packageName")
        }
    }

    private fun shouldSkipPackage(packageName: String): Boolean {
        // System apps and our own app
        // NOTE: Only list actual system UI/infrastructure packages here.
        // Do NOT add user-facing apps like com.android.chrome, com.android.mms,
        // com.android.messaging, com.android.email, com.android.browser, com.android.vending
        // — those should be monitored for child safety.
        val skipPackages = setOf(
            "com.safeguard.parentalcontrol",
            // Android system UI & infrastructure
            "com.android.systemui",
            // Home launchers — the home screen only shows app labels, never user-typed
            // content, so monitoring it just produces false positives (e.g. the app
            // drawer text being flagged). OEM launchers below; any other is caught
            // dynamically via launcherPackages (resolved from the HOME intent).
            "com.android.launcher",
            "com.android.launcher3",
            "com.sec.android.app.launcher",          // Samsung One UI Home
            "com.google.android.apps.nexuslauncher",  // Pixel
            "com.miui.home",                          // Xiaomi
            "com.huawei.android.launcher",            // Huawei / Honor
            "com.oppo.launcher",                      // Oppo
            "com.oneplus.launcher",                   // OnePlus
            "com.microsoft.launcher",                 // Microsoft Launcher
            "com.teslacoilsw.launcher",               // Nova
            "com.android.settings",
            "com.android.providers.settings",
            "com.android.providers.media",
            "com.android.providers.contacts",
            "com.android.providers.telephony",
            "com.android.providers.calendar",
            "com.android.providers.downloads",
            "com.android.providers.userdictionary",
            "com.android.providers.blockednumber",
            "com.android.server.telecom",
            "com.android.phone",
            "com.android.incallui",
            "com.android.stk",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.android.shell",
            "com.android.se",
            "com.android.nfc",
            "com.android.bluetooth",
            "com.android.printspooler",
            "com.android.wallpaper",
            "com.android.wallpapercropper",
            "com.android.documentsui",
            "com.android.externalstorage",
            "com.android.vpndialogs",
            "com.android.certinstaller",
            "com.android.carrierconfig",
            // Keyboards
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.swiftkey.languageprovider"
        )

        // SECURITY: Sensitive apps we must NEVER monitor
        // Banking, payment, password managers, authenticators
        val sensitiveApps = setOf(
            // Password managers
            "com.lastpass.lpandroid",
            "com.agilebits.onepassword",
            "com.dashlane",
            "com.bitwarden.android",
            "keepass2android.keepass2android",
            "com.kunzisoft.keepass.free",
            "org.pwsafe.android",
            // Banking apps (common ones)
            "com.chase.sig.android",
            "com.wf.wellsfargomobile",
            "com.bankofamerica.cashpromobile",
            "com.citi.citimobile",
            "com.usbank.mobilebanking",
            "com.ally.MobileBanking",
            "com.capitalone.mobile",
            "com.schwab.mobile",
            "com.paypal.android.p2pmobile",
            "com.venmo",
            "com.squareup.cash",
            "com.zellepay.zelle",
            // Additional banking & financial apps
            "com.tdbank",
            "com.pnc.ecommerce.mobile",
            "com.huntington.m",
            "com.key.android",
            "com.regions.mobbanking",
            "com.mtb.mbanking.sc.retail.prod",
            "com.bbt.myfi",
            "com.suntrust.mobilebanking",
            "com.citizensbank.androidapp",
            "com.discover.mobile",
            "com.americanexpress.android.acctsvcs.us",
            "com.navyfederal.android",
            "com.usaa.mobile.android.usaa",
            // Brokerage & investment
            "com.robinhood.android",
            "com.fidelity.android",
            "com.etrade.mobilepro.activity",
            "com.thinkorswim.tablet",
            "com.interactivebrokers.ibkr",
            "com.webull.broker",
            // International banking
            "com.rbs.mobile.android.natwest",
            "com.barclays.android.barclaysmobilebanking",
            "uk.co.hsbc.hsbcukmobilebanking",
            "com.revolut.revolut",
            "com.starlingbank.android",
            "com.monzo.android",
            "com.n26.android",
            // Payment services
            "com.google.android.apps.walletnfcrel",
            "com.samsung.android.spay",
            "com.stripe.android.dashboard",
            "com.affirm.central",
            "com.klarna.mobile",
            // Authenticators
            "com.google.android.apps.authenticator2",
            "com.microsoft.msa.authenticator",
            "com.authy.authy",
            "org.fedorahosted.freeotp",
            "com.yubico.yubioath",
            // Crypto wallets
            "com.coinbase.android",
            "piuk.blockchain.android",
            "com.binance.dev",
            "com.wallet.crypto.trustapp"
        )

        if (packageName in skipPackages ||
            packageName in sensitiveApps ||
            packageName in launcherPackages
        ) {
            return true
        }

        // Skip by content patterns (banking, passwords, auth, wallets, finance, payments)
        val lowerPkg = packageName.lowercase()
        if (lowerPkg.contains("banking") ||
            lowerPkg.contains(".bank.") ||
            lowerPkg.contains("password") ||
            lowerPkg.contains("authenticator") ||
            lowerPkg.contains("wallet") ||
            lowerPkg.contains("finance") ||
            lowerPkg.contains("fintech") ||
            lowerPkg.contains("payment") ||
            lowerPkg.contains("invest") ||
            lowerPkg.contains("brokerage") ||
            lowerPkg.contains("insurance") ||
            lowerPkg.contains("mortgage") ||
            lowerPkg.contains("creditcard") ||
            lowerPkg.contains("mobilebank")) {
            return true
        }

        return false
    }

    private fun getAppName(packageName: String): String? {
        return try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            null
        }
    }

    override fun onInterrupt() {
        Timber.w("onInterrupt() - Service interrupted by Android")
        Timber.d("TextMonitoringAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("TextMonitoringAccessibilityService destroying - cleaning up resources")

        // Cancel all pending analysis jobs first
        pendingAnalysis.values.forEach { it.cancel() }
        pendingAnalysis.clear()

        // Clear the timestamp tracking map to release memory
        packageLastEventTime.clear()

        // Cancel the entire service scope (cancels all child coroutines)
        // Using cancel() on the job ensures all coroutines are properly terminated
        if (::serviceJob.isInitialized) {
            serviceJob.cancel()
        }

        // Reset rate limiting state
        eventCount = 0

        Timber.d("TextMonitoringAccessibilityService destroyed - all resources cleaned up")
    }

    /**
     * Called when the service is disconnected. Clean up any callbacks or listeners.
     */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Timber.d("TextMonitoringAccessibilityService unbinding")
        // Perform cleanup similar to onDestroy for safety
        pendingAnalysis.values.forEach { it.cancel() }
        return super.onUnbind(intent)
    }
}
