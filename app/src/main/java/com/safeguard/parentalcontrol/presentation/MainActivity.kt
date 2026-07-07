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
import com.safeguard.parentalcontrol.util.AuthEvent
import com.safeguard.parentalcontrol.util.LocaleHelper
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.TokenManager
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

        setContent {
            SafeGuardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SafeGuardApp(
                        isLoggedIn = tokenManager.isLoggedIn(),
                        tokenManager = tokenManager
                    )
                }
            }
        }
    }
}

@Composable
fun SafeGuardApp(
    isLoggedIn: Boolean,
    tokenManager: TokenManager
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
        startDestination = Screen.Splash.route
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
