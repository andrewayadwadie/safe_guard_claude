package com.safeguard.parentalcontrol.presentation

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.safeguard.parentalcontrol.presentation.navigation.SafeGuardNavGraph
import com.safeguard.parentalcontrol.presentation.navigation.Screen
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme
import com.safeguard.parentalcontrol.service.MonitoringService
import com.safeguard.parentalcontrol.util.AlertPipe
import com.safeguard.parentalcontrol.util.AuthEvent
import com.safeguard.parentalcontrol.util.Constants
import com.safeguard.parentalcontrol.util.DeepLinkHolder
import com.safeguard.parentalcontrol.util.LocaleHelper
import com.safeguard.parentalcontrol.util.PendingDeepLink
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.TokenManager
import com.safeguard.parentalcontrol.worker.PushTokenSyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import javax.inject.Inject

/**
 * Main activity - entry point for the application
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var deepLinkHolder: DeepLinkHolder

    @Inject
    lateinit var pushTokenSyncScheduler: PushTokenSyncScheduler

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the splash screen before super.onCreate so the Haris logo shows during startup
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start MonitoringService if user is logged in and device is registered
        // This ensures enforcement works even if the service wasn't started on boot
        startMonitoringServiceIfNeeded()

        // Publish this device's push token on every start with a live session. Registering
        // only at sign-in would leave anyone already signed in before this shipped
        // permanently unreachable, since they never sign in again.
        if (tokenManager.isLoggedIn()) {
            pushTokenSyncScheduler.schedule()
        }

        // Cold start from a notification tap.
        handleDeepLinkIntent(intent)

        setContent {
            SafeGuardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SafeGuardApp(
                        isLoggedIn = tokenManager.isLoggedIn(),
                        tokenManager = tokenManager,
                        deepLinkHolder = deepLinkHolder
                    )
                }
            }
        }
    }

    /**
     * The activity is declared singleTop, so a tap while the app is already running arrives
     * here instead of through a fresh onCreate. Replacing the stored intent keeps
     * getIntent() consistent for anything that reads it later.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    /**
     * Record where a violation notification wants to go. The navigation itself happens in the
     * nav graph, which consumes the value exactly once.
     */
    private fun handleDeepLinkIntent(intent: Intent?) {
        if (intent == null) return

        val alertIdExtra = intent.getIntExtra(Constants.EXTRA_ALERT_ID, -1).takeIf { it > 0 }
        val targetsAlerts = intent.getStringExtra(Constants.EXTRA_NAV_TARGET) == Constants.NAV_TARGET_ALERTS
        // An alert id on its own is target enough — there is nowhere else it could mean.
        if (!targetsAlerts && alertIdExtra == null) return

        // A violation is parent-facing by design; surfacing it on the monitored device teaches
        // evasion. Only a positively-known child role is refused — an unknown role still flows
        // through, because the target must survive the login detour an expired session forces.
        if (preferencesManager.isChild) {
            Timber.d("Alerts deep link on a child device; discarded")
            AlertPipe.w("DEEPLINK DISCARDED role=child alert_id=$alertIdExtra")
            return
        }

        val deviceId = intent.getIntExtra(Constants.EXTRA_DEVICE_ID, -1).takeIf { it != -1 }
        val deviceName = intent.getStringExtra(Constants.EXTRA_DEVICE_NAME)
        deepLinkHolder.post(
            PendingDeepLink(deviceId = deviceId, deviceName = deviceName, alertId = alertIdExtra)
        )
        Timber.d("Violation notification tapped; alerts deep link pending")
        AlertPipe.i("DEEPLINK tapped device_db_id=$deviceId alert_id=$alertIdExtra")
    }
}

@Composable
fun SafeGuardApp(
    isLoggedIn: Boolean,
    tokenManager: TokenManager,
    deepLinkHolder: DeepLinkHolder
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // Observe auth events and redirect to login when session expires or token is revoked
    LaunchedEffect(Unit) {
        tokenManager.authEvents.collectLatest { event ->
            when (event) {
                is AuthEvent.SessionExpired -> {
                    Timber.d("Session expired, redirecting to login")
                    Toast.makeText(context, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
                    navigateToLogin(navController)
                }
                is AuthEvent.TokenRevoked -> {
                    Timber.d("Token revoked, redirecting to login")
                    Toast.makeText(context, "Your session was ended. Please log in again.", Toast.LENGTH_LONG).show()
                    navigateToLogin(navController)
                }
                is AuthEvent.ManualLogout -> {
                    // Manual logout is handled by the logout flow, no need for toast
                    Timber.d("Manual logout")
                }
            }
        }
    }

    SafeGuardNavGraph(
        navController = navController,
        isLoggedIn = isLoggedIn,
        startDestination = Screen.Splash.route,
        deepLinkHolder = deepLinkHolder
    )
}

/**
 * Navigate to login screen, clearing the back stack
 */
private fun navigateToLogin(navController: NavHostController) {
    navController.navigate(Screen.Login.route) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Start MonitoringService if user is logged in and device is registered.
 * This is called on every app open to ensure the service is running.
 * The service handles being started multiple times gracefully (START_STICKY).
 */
private fun MainActivity.startMonitoringServiceIfNeeded() {
    try {
        if (preferencesManager.shouldRunMonitoring) {
            Timber.d("MainActivity: Starting MonitoringService (logged in, registered, consent granted)")
            val serviceIntent = Intent(this, MonitoringService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Timber.d("MainActivity: MonitoringService start command sent")
        } else {
            Timber.d("MainActivity: Not starting MonitoringService (loggedIn=${preferencesManager.isLoggedIn}, deviceRegistered=${preferencesManager.isDeviceRegistered}, consent=${preferencesManager.monitoringConsentGranted})")
        }
    } catch (e: Exception) {
        Timber.e(e, "MainActivity: Failed to start MonitoringService")
    }
}
