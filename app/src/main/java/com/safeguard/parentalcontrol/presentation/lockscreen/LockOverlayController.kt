package com.safeguard.parentalcontrol.presentation.lockscreen

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.AlertSeverity
import com.safeguard.parentalcontrol.data.model.AlertType
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme
import com.safeguard.parentalcontrol.util.LocaleHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows the lock screen as a system overlay window ([WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY])
 * instead of an Activity.
 *
 * Why this exists: the Activity-based lock ([LockScreenActivity]) is a fullscreen *task* window, and
 * Android always renders PINNED (Picture-in-Picture) and freeform / pop-up task windows ABOVE the
 * fullscreen task stack - so a child can float YouTube (PiP or Samsung pop-up view) on top of the lock
 * and keep using it. A TYPE_APPLICATION_OVERLAY window sits in the overlay layer band, above those task
 * windows, so it actually covers them. Requires the SYSTEM_ALERT_WINDOW ("display over other apps")
 * permission, which SafeGuard already requests.
 *
 * The overlay reuses the exact same [LockScreen] composable the Activity renders, so the UI is identical.
 *
 * Phone safety: the overlay is opaque and on top of everything, so it must never cover an active call
 * or the dialer. [show] is a no-op while a call is active or a phone/dialer app is foreground, and
 * [launchEmergencyDialer] removes the overlay before opening the dialer. The enforcement loop in
 * MonitoringService is the other authority - it dismisses the lock when a call is active.
 */
@Singleton
class LockOverlayController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alertRepository: AlertRepository,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager: WindowManager
        get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var host: OverlayComposeHost? = null

    /** The lock type currently displayed by the overlay, or null when hidden. Read off the main thread. */
    @Volatile
    var currentLockType: String? = null
        private set

    fun isShowing(): Boolean = currentLockType != null

    fun isShowingForType(lockType: String): Boolean = currentLockType == lockType

    /**
     * Show (or re-target) the lock overlay. Safe to call from any thread; work is marshalled to main.
     * No-ops while a call/dialer is foreground so the overlay never covers a call.
     */
    fun show(lockType: String, message: String, appName: String?) = runOnMain {
        if (isPhoneCallActive() || isPhoneAppInForeground()) {
            Timber.d("LockOverlay: phone/dialer active - not showing $lockType")
            return@runOnMain
        }

        val existing = host
        if (existing != null) {
            if (currentLockType == lockType) return@runOnMain
            // Same window, different reason: just swap the content.
            existing.setContent(buildContent(lockType, message, appName))
            currentLockType = lockType
            Timber.d("LockOverlay: re-targeted to $lockType")
            return@runOnMain
        }

        val newHost = OverlayComposeHost(context)
        try {
            newHost.attach(windowManager, buildContent(lockType, message, appName))
        } catch (e: Exception) {
            Timber.e(e, "LockOverlay: failed to add overlay window")
            newHost.safeDetach(windowManager)
            return@runOnMain
        }
        host = newHost
        currentLockType = lockType
        Timber.d("LockOverlay: shown for $lockType")
    }

    /** Remove the lock overlay if present. Safe to call from any thread. */
    fun hide() = runOnMain {
        host?.safeDetach(windowManager)
        host = null
        currentLockType = null
    }

    private fun buildContent(lockType: String, message: String, appName: String?): @Composable () -> Unit = {
        SafeGuardTheme {
            LockScreen(
                lockType = lockType,
                message = message,
                appName = appName,
                onDismiss = { hide() },
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

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(context).getString(resId, *args)

    /**
     * Reuse the existing alert pipeline (POST /alerts -> FCM to the parent) to deliver a child request.
     * Fire-and-forget: the child UI confirms optimistically; failures are logged.
     */
    private fun sendParentRequest(alertType: AlertType, title: String, message: String, lockTypeMeta: String) {
        scope.launch {
            try {
                alertRepository.createAlert(
                    alertType = alertType,
                    severity = AlertSeverity.MEDIUM,
                    title = title,
                    message = message,
                    metadata = mapOf("source" to "lock_screen_request", "lock_type" to lockTypeMeta)
                )
            } catch (e: Exception) {
                Timber.e("LockOverlay: failed to send parent request: ${e.message}")
            }
        }
    }

    /**
     * Open the system dialer for an emergency call. Removes the overlay first (an opaque overlay
     * would otherwise cover the dialer), then relies on the phone/dialer guard in [show] to keep the
     * lock from re-covering it while the dialer is foreground.
     */
    private fun launchEmergencyDialer() {
        hide()
        try {
            context.startActivity(
                Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (e: Exception) {
            Timber.e("LockOverlay: failed to open emergency dialer: ${e.message}")
        }
    }

    private fun isPhoneCallActive(): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            (tm?.callState ?: TelephonyManager.CALL_STATE_IDLE) != TelephonyManager.CALL_STATE_IDLE
        } catch (e: Exception) {
            false
        }
    }

    private fun isPhoneAppInForeground(): Boolean {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return false
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 5000, end)
            val event = UsageEvents.Event()
            var topApp: String? = null
            var topTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp > topTime) {
                    topTime = event.timeStamp
                    topApp = event.packageName
                }
            }
            topApp != null && PHONE_DIALER_PACKAGES.contains(topApp)
        } catch (e: Exception) {
            false
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post {
                try {
                    block()
                } catch (e: Exception) {
                    Timber.e(e, "LockOverlay: error on main thread")
                }
            }
        }
    }

    companion object {
        // Phone/dialer packages that must never be covered by the lock (parity with LockScreenActivity).
        private val PHONE_DIALER_PACKAGES = setOf(
            "com.android.phone",
            "com.android.dialer",
            "com.android.incallui",
            "com.android.server.telecom",
            "com.google.android.dialer",
            "com.samsung.android.incallui",
            "com.samsung.android.dialer",
            "com.samsung.android.contacts",
            "com.sec.android.app.telephonyui",
            "com.oneplus.dialer",
            "com.oneplus.contacts",
            "com.huawei.contacts",
            "com.miui.contacts",
            "com.android.contacts",
        )
    }
}

/**
 * Hosts a Compose UI inside a WindowManager-added overlay (no Activity). Compose needs a
 * LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner in the view tree; this class
 * supplies all three for the detached overlay window. The root view swallows BACK so the lock
 * cannot be dismissed with the hardware/gesture back action.
 */
internal class OverlayComposeHost(
    private val context: Context,
) : SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var root: View? = null
    private var composeView: ComposeView? = null

    fun attach(windowManager: WindowManager, content: @Composable () -> Unit) {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val cv = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent(content)
        }

        val container = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                // The whole point of the lock: BACK cannot dismiss it.
                if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusableInTouchMode = true
        }

        container.setViewTreeLifecycleOwner(this)
        container.setViewTreeViewModelStoreOwner(this)
        container.setViewTreeSavedStateRegistryOwner(this)
        container.addView(cv)

        windowManager.addView(container, buildLayoutParams())
        root = container
        composeView = cv
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun setContent(content: @Composable () -> Unit) {
        composeView?.setContent(content)
    }

    fun safeDetach(windowManager: WindowManager) {
        try {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            root?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Timber.e(e, "LockOverlay: detach error")
        } finally {
            viewModelStore.clear()
            root = null
            composeView = null
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_SECURE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }
}
