package com.safeguard.parentalcontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.ml.ContentClassifier
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
}

@AndroidEntryPoint
class TextMonitoringAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TextMonitorAccessSvc"

        // Static flag to track if service has ever been created (survives service restarts)
        @Volatile
        var serviceEverCreated = false
            private set

        init {
            // This logs when the class is LOADED (before any instance is created)
            // Using Log.d instead of Timber in case Timber isn't initialized yet
            Log.d(TAG, "CLASS LOADED - TextMonitoringAccessibilityService class initialized")
        }
    }

    @Inject
    lateinit var alertRepository: AlertRepository

    @Inject
    lateinit var contentClassifier: ContentClassifier

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

    override fun onCreate() {
        // Use Log.d first in case Timber isn't initialized yet
        Log.d(TAG, "onCreate() CALLED - Service instance being created")
        serviceEverCreated = true

        super.onCreate()
        Log.d(TAG, "onCreate() - super.onCreate() completed")

        try {
            // Initialize coroutine scope with SupervisorJob for proper lifecycle management
            serviceJob = SupervisorJob()
            serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
            Log.d(TAG, "onCreate() - CoroutineScope initialized")

            // Check if Hilt injection was successful
            isInitialized = try {
                val repoInit = ::alertRepository.isInitialized
                val classifierInit = ::contentClassifier.isInitialized
                Log.d(TAG, "onCreate() - Hilt check: alertRepository=$repoInit, contentClassifier=$classifierInit")
                repoInit && classifierInit
            } catch (e: Exception) {
                Log.w(TAG, "onCreate() - Hilt isInitialized check failed: ${e.message}")
                false
            }

            // If automatic Hilt injection failed, try manual injection via EntryPoint
            if (!isInitialized) {
                Log.w(TAG, "onCreate() - Automatic Hilt injection failed, attempting manual injection")
                Timber.w("TextMonitoringAccessibilityService: Automatic Hilt injection failed, attempting manual injection")
                try {
                    val entryPoint = EntryPointAccessors.fromApplication(
                        applicationContext,
                        TextMonitoringAccessibilityServiceEntryPoint::class.java
                    )
                    alertRepository = entryPoint.alertRepository()
                    contentClassifier = entryPoint.contentClassifier()
                    isInitialized = true
                    Log.d(TAG, "onCreate() - Manual EntryPoint injection successful")
                    Timber.d("TextMonitoringAccessibilityService: Manual injection successful")
                } catch (e: Exception) {
                    Log.e(TAG, "onCreate() - Manual injection FAILED: ${e.message}", e)
                    Timber.e(e, "TextMonitoringAccessibilityService: Manual injection also failed")
                    isInitialized = false
                }
            }

            if (!isInitialized) {
                Log.e(TAG, "onCreate() - ALL INJECTION FAILED - service will NOT function")
                Timber.e("TextMonitoringAccessibilityService: All injection attempts failed - service will not function")
            } else {
                Log.i(TAG, "onCreate() - SUCCESS - Service created and initialized")
                Timber.d("TextMonitoringAccessibilityService created and initialized successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate() - EXCEPTION during initialization: ${e.message}", e)
            Timber.e(e, "TextMonitoringAccessibilityService: Error during onCreate")
            isInitialized = false
        }
    }

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected() CALLED - Android has connected to our service!")
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
            Log.d(TAG, "onServiceConnected() - serviceInfo configured: eventTypes=${serviceInfo?.eventTypes}")

            // Re-check initialization in case dependencies weren't ready during onCreate
            if (!isInitialized) {
                Log.w(TAG, "onServiceConnected() - Not initialized in onCreate, retrying...")
                Timber.w("TextMonitoringAccessibilityService: Not initialized during onCreate, retrying in onServiceConnected")
                try {
                    val entryPoint = EntryPointAccessors.fromApplication(
                        applicationContext,
                        TextMonitoringAccessibilityServiceEntryPoint::class.java
                    )
                    alertRepository = entryPoint.alertRepository()
                    contentClassifier = entryPoint.contentClassifier()
                    isInitialized = true
                    Log.d(TAG, "onServiceConnected() - Late initialization SUCCESS")
                    Timber.d("TextMonitoringAccessibilityService: Late initialization successful in onServiceConnected")
                } catch (e: Exception) {
                    Log.e(TAG, "onServiceConnected() - Late initialization FAILED: ${e.message}", e)
                    Timber.e(e, "TextMonitoringAccessibilityService: Late initialization failed")
                }
            }

            Log.i(TAG, "onServiceConnected() COMPLETE - isInitialized=$isInitialized, ready for events")
            Timber.d("TextMonitoringAccessibilityService: isInitialized=$isInitialized, ready for text monitoring")
        } catch (e: Exception) {
            Log.e(TAG, "onServiceConnected() EXCEPTION: ${e.message}", e)
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
            Log.w(TAG, "onAccessibilityEvent() - Not initialized, attempting on-demand injection")
            // Try to re-initialize via EntryPoint (last resort)
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    applicationContext,
                    TextMonitoringAccessibilityServiceEntryPoint::class.java
                )
                alertRepository = entryPoint.alertRepository()
                contentClassifier = entryPoint.contentClassifier()
                isInitialized = true
                Log.d(TAG, "onAccessibilityEvent() - On-demand initialization SUCCESS")
                Timber.d("TextMonitoringAccessibilityService: On-demand initialization successful")
            } catch (e: Exception) {
                // Still not initialized - silently skip this event
                Log.e(TAG, "onAccessibilityEvent() - On-demand initialization FAILED: ${e.message}")
                return
            }
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
                event.text?.joinToString(" ") ?: ""
            }
        } catch (e: Exception) {
            Timber.v("Error extracting text from event: ${e.message}")
            ""
        }

        // Skip if text is too short or blank
        // Minimum 3 characters to catch single words like "sex", "porn", etc.
        if (extractedText.isBlank() || extractedText.length < 3) return

        // Log extracted text for debugging (only log first 100 chars to avoid spam)
        val textPreview = if (extractedText.length > 100) extractedText.take(100) + "..." else extractedText
        Log.d(TAG, "Extracted text from $packageName ($eventTypeName): '$textPreview'")

        // Schedule debounced analysis with already-extracted text
        pendingAnalysis[packageName] = serviceScope.launch {
            delay(500) // Additional short delay for batching rapid changes
            handleTextEvent(extractedText, packageName)
        }
    }

    private suspend fun handleTextEvent(text: String, packageName: String) {
        try {
            Log.d(TAG, "handleTextEvent: Analyzing text from $packageName (${text.length} chars)")
            Timber.d("Analyzing text from $packageName: length=${text.length}, preview='${text.take(50)}...'")
            analyzeText(packageName, text)
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Log.e(TAG, "handleTextEvent: Error - ${e.message}")
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
            viewId.contains("pin") ||
            viewId.contains("secret") ||
            viewId.contains("credit") ||
            viewId.contains("card") ||
            viewId.contains("cvv") ||
            viewId.contains("cvc") ||
            viewId.contains("ssn") ||
            viewId.contains("social_security")) {
            return true
        }

        // Check content description and hint
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val hintText = node.hintText?.toString()?.lowercase() ?: ""

        val sensitiveKeywords = listOf("password", "pin", "credit card", "cvv", "security code", "ssn")
        for (keyword in sensitiveKeywords) {
            if (contentDesc.contains(keyword) || hintText.contains(keyword)) {
                return true
            }
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
            Log.d(TAG, "analyzeText: Calling contentClassifier.analyzeText()")
            val result = contentClassifier.analyzeText(text)
            Log.d(TAG, "analyzeText: Result - isFlagged=${result.isFlagged}, categories=${result.categories}, confidence=${result.confidence}")

            if (result.isFlagged) {
                val appName = getAppName(packageName)
                Log.w(TAG, "FLAGGED! Inappropriate text detected in $packageName")
                Timber.w("FLAGGED: Inappropriate text in $packageName: categories=${result.categories}, confidence=${result.confidence}, reason=${result.reason}")

                // Send alert with full context (categories, confidence, text for deduplication)
                // Note: The actual text is only used for hashing (deduplication), never stored or transmitted
                val alertResult = alertRepository.createInappropriateTextAlert(
                    appPackage = packageName,
                    appName = appName,
                    reason = result.reason ?: result.categories.joinToString(", "),
                    categories = result.categories,
                    confidence = result.confidence,
                    textForDedup = text // Used for hash-based deduplication only
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
        val skipPackages = setOf(
            "com.safeguard.parentalcontrol",
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.android.settings",
            "com.google.android.inputmethod.latin", // Keyboard
            "com.samsung.android.honeyboard", // Samsung keyboard
            "com.swiftkey.languageprovider" // SwiftKey
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

        if (packageName in skipPackages || packageName in sensitiveApps) {
            return true
        }

        // Skip by prefix patterns
        if (packageName.startsWith("com.android.") ||
            packageName.startsWith("com.google.android.inputmethod") ||
            packageName.contains(".banking.") ||
            packageName.contains(".bank.") ||
            packageName.contains("password") ||
            packageName.contains("authenticator") ||
            packageName.contains("wallet")) {
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
        Log.w(TAG, "onInterrupt() - Service interrupted by Android")
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
