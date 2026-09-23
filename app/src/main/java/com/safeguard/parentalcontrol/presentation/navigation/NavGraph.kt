package com.safeguard.parentalcontrol.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.presentation.alerts.AlertsScreen
import com.safeguard.parentalcontrol.presentation.auth.LoginScreen
import com.safeguard.parentalcontrol.presentation.auth.RegisterScreen
import com.safeguard.parentalcontrol.presentation.forgotpassword.ForgotPasswordScreen
import com.safeguard.parentalcontrol.presentation.forgotpassword.ForgotPasswordViewModel
import com.safeguard.parentalcontrol.presentation.forgotpassword.ResetPasswordScreen
import com.safeguard.parentalcontrol.presentation.blacklist.BlacklistScreen
import com.safeguard.parentalcontrol.presentation.children.ChildrenScreen
import com.safeguard.parentalcontrol.presentation.dashboard.DashboardScreen
import com.safeguard.parentalcontrol.presentation.devices.DevicesScreen
import com.safeguard.parentalcontrol.presentation.devicesetup.DeviceSetupScreen
import com.safeguard.parentalcontrol.presentation.imagereview.ImageReviewScreen
import com.safeguard.parentalcontrol.presentation.textreview.TextReviewScreen
import com.safeguard.parentalcontrol.presentation.linkparent.LinkParentScreen
import com.safeguard.parentalcontrol.presentation.screentimelimits.ScreenTimeLimitsScreen
import com.safeguard.parentalcontrol.presentation.settings.ChangePasswordScreen
import com.safeguard.parentalcontrol.presentation.settings.LegalDocScreen
import com.safeguard.parentalcontrol.presentation.settings.SettingsScreen
import com.safeguard.parentalcontrol.presentation.settings.TextMonitoringSettingsScreen
import com.safeguard.parentalcontrol.presentation.setup.ConsentScreen
import com.safeguard.parentalcontrol.presentation.setup.PermissionsSetupScreen
import com.safeguard.parentalcontrol.presentation.splash.SplashScreen
import com.safeguard.parentalcontrol.presentation.wordlist.WordListScreen
import com.safeguard.parentalcontrol.util.DeepLinkHolder
import com.safeguard.parentalcontrol.util.PendingDeepLink
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Route of the nested graph that hosts the two forgot-password steps. */
private const val FORGOT_PASSWORD_FLOW_ROUTE = "forgot_password_flow"

/** Result key set on the Login entry when a password reset completes. */
private const val PASSWORD_RESET_SUCCESS_KEY = "password_reset_success"

/**
 * Navigate to the existing Alerts route for a pending notification deep link.
 *
 * Reuses [Screen.Alerts] rather than introducing a destination: launchSingleTop keeps a
 * second tap from stacking another copy when the parent is already looking at the screen.
 */
private fun NavHostController.navigateToAlerts(link: PendingDeepLink) {
    navigate(Screen.Alerts.createRoute(link.deviceId, link.deviceName, link.alertId)) {
        launchSingleTop = true
    }
}

/**
 * Navigation routes
 */
sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object Dashboard : Screen("dashboard")
    data object DeviceSetup : Screen("device_setup")
    data object Consent : Screen("consent")
    data object PermissionsSetup : Screen("permissions_setup/{isFromSetupFlow}") {
        fun createRoute(isFromSetupFlow: Boolean = false): String = "permissions_setup/$isFromSetupFlow"
    }
    data object Settings : Screen("settings")
    data object ChangePassword : Screen("change_password")

    /**
     * Forgot-password recovery flow. Both steps live in a nested graph
     * ([FORGOT_PASSWORD_FLOW_ROUTE]) so they share one graph-scoped ViewModel.
     */
    data object ForgotPassword : Screen("forgot_password_email")
    data object ResetPassword : Screen("forgot_password_reset")

    /**
     * In-app legal document viewer. `doc` selects which bundled HTML to show
     * ("privacy" or "terms").
     */
    data object Legal : Screen("legal/{doc}") {
        fun createRoute(doc: String): String = "legal/$doc"
    }
    data object Alerts : Screen("alerts?deviceId={deviceId}&deviceName={deviceName}&alertId={alertId}") {
        fun createRoute(deviceId: Int? = null, deviceName: String? = null, alertId: Int? = null): String {
            // No device means the unfiltered list. A device without a name still filters —
            // a violation push may identify the device without carrying its display name.
            // An alertId can arrive with or without a device, so it is appended independently.
            val params = buildList {
                deviceId?.let { add("deviceId=$it") }
                deviceName?.let { add("deviceName=" + URLEncoder.encode(it, StandardCharsets.UTF_8.toString())) }
                alertId?.let { add("alertId=$it") }
            }
            return if (params.isEmpty()) "alerts" else "alerts?" + params.joinToString("&")
        }
    }
    data object Devices : Screen("devices")
    data object ScreenTime : Screen("screen_time")
    data object Children : Screen("children")

    /**
     * Child's devices screen - shows devices for a specific child (parent only)
     */
    data object ChildDevices : Screen("child_devices/{childId}/{childName}") {
        fun createRoute(childId: Int, childName: String): String {
            val encodedName = URLEncoder.encode(childName, StandardCharsets.UTF_8.toString())
            return "child_devices/$childId/$encodedName"
        }
    }

    /**
     * Blacklist management screen with device parameters
     */
    data object Blacklist : Screen("blacklist/{deviceId}/{deviceName}") {
        fun createRoute(deviceId: Int, deviceName: String): String {
            val encodedName = URLEncoder.encode(deviceName, StandardCharsets.UTF_8.toString())
            return "blacklist/$deviceId/$encodedName"
        }
    }

    /**
     * Custom word list management screen (parent only)
     */
    data object WordList : Screen("wordlist")

    /**
     * Text monitoring settings screen (parent only)
     */
    data object TextMonitoringSettings : Screen("text_monitoring_settings")

    /**
     * Image review screen for parents to review blurred images on child device
     */
    data object ImageReview : Screen("image_review")
    data object TextReview : Screen("text_review")

    /**
     * Child-side screen that shows a pairing code for a parent to link with.
     */
    data object LinkParent : Screen("link_parent")

    /**
     * Screen time limits management screen with device parameters (parent only)
     */
    data object ScreenTimeLimits : Screen("screen_time_limits/{deviceId}/{deviceName}") {
        fun createRoute(deviceId: Int, deviceName: String): String {
            val encodedName = URLEncoder.encode(deviceName, StandardCharsets.UTF_8.toString())
            return "screen_time_limits/$deviceId/$encodedName"
        }
    }
}

/**
 * Main navigation graph
 */
@Composable
fun SafeGuardNavGraph(
    navController: NavHostController = rememberNavController(),
    isLoggedIn: Boolean = false,
    startDestination: String = Screen.Splash.route,
    deepLinkHolder: DeepLinkHolder
) {
    val pendingDeepLink by deepLinkHolder.pending.collectAsStateWithLifecycle()

    // A violation notification was tapped while a session is live: go straight to the alert.
    // Consumed in the same effect, so screen recreation (rotation) finds nothing pending and
    // does not navigate a second time.
    LaunchedEffect(pendingDeepLink, isLoggedIn) {
        val link = pendingDeepLink ?: return@LaunchedEffect
        if (!isLoggedIn) return@LaunchedEffect  // resumed after login instead - see below
        navController.navigateToAlerts(link)
        deepLinkHolder.consume()
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Splash Screen (animated logo, shown on cold launch)
        composable(Screen.Splash.route) {
            SplashScreen(
                onFinished = {
                    val next = if (isLoggedIn) Screen.Dashboard.route else Screen.Login.route
                    navController.navigate(next) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        // Login Screen
        composable(Screen.Login.route) { backStackEntry ->
            val passwordResetSuccess by backStackEntry.savedStateHandle
                .getStateFlow(PASSWORD_RESET_SUCCESS_KEY, false)
                .collectAsStateWithLifecycle()

            LoginScreen(
                onNavigateToRegister = {
                    navController.navigate(Screen.Register.route)
                },
                onLoginSuccess = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                    // The parent got here by tapping a violation notification with an
                    // expired session. Now that they are authenticated, continue to the
                    // alert they were trying to reach instead of dropping it. Dashboard
                    // stays underneath so Back behaves normally.
                    deepLinkHolder.pending.value?.let { link ->
                        navController.navigateToAlerts(link)
                        deepLinkHolder.consume()
                    }
                },
                onNeedDeviceSetup = {
                    navController.navigate(Screen.DeviceSetup.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateToForgotPassword = {
                    navController.navigate(FORGOT_PASSWORD_FLOW_ROUTE)
                },
                passwordResetSuccess = passwordResetSuccess,
                onPasswordResetSuccessShown = {
                    backStackEntry.savedStateHandle[PASSWORD_RESET_SUCCESS_KEY] = false
                }
            )
        }

        // Forgot-password recovery flow (nested graph — shared graph-scoped ViewModel)
        navigation(
            route = FORGOT_PASSWORD_FLOW_ROUTE,
            startDestination = Screen.ForgotPassword.route
        ) {
            composable(Screen.ForgotPassword.route) { entry ->
                val parentEntry = remember(entry) { navController.getBackStackEntry(FORGOT_PASSWORD_FLOW_ROUTE) }
                val viewModel: ForgotPasswordViewModel = hiltViewModel(parentEntry)
                ForgotPasswordScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onCodeSent = { navController.navigate(Screen.ResetPassword.route) }
                )
            }
            composable(Screen.ResetPassword.route) { entry ->
                val parentEntry = remember(entry) { navController.getBackStackEntry(FORGOT_PASSWORD_FLOW_ROUTE) }
                val viewModel: ForgotPasswordViewModel = hiltViewModel(parentEntry)
                ResetPasswordScreen(
                    viewModel = viewModel,
                    onBackToEmail = { navController.popBackStack() },
                    onResetSuccess = {
                        navController.getBackStackEntry(Screen.Login.route)
                            .savedStateHandle[PASSWORD_RESET_SUCCESS_KEY] = true
                        navController.popBackStack(Screen.Login.route, inclusive = false)
                    }
                )
            }
        }

        // Register Screen
        composable(Screen.Register.route) {
            RegisterScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onRegisterSuccess = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNeedDeviceSetup = {
                    navController.navigate(Screen.DeviceSetup.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        // Device Setup Screen (for child users who need to register their device)
        composable(Screen.DeviceSetup.route) {
            DeviceSetupScreen(
                onSetupComplete = {
                    // After device setup, the parent must accept the monitoring disclosure
                    // before any permission is requested or any service can run.
                    navController.navigate(Screen.Consent.route) {
                        popUpTo(Screen.DeviceSetup.route) { inclusive = true }
                    }
                }
            )
        }

        // Monitoring disclosure + consent (child setup flow). Gates the consent flag
        // that every monitoring service checks before it may run.
        composable(Screen.Consent.route) {
            ConsentScreen(
                onConsentGranted = {
                    navController.navigate(Screen.PermissionsSetup.createRoute(isFromSetupFlow = true)) {
                        popUpTo(Screen.Consent.route) { inclusive = true }
                    }
                },
                onNavigateBack = { navController.popBackStack() },
                onNavigateToPrivacyPolicy = { navController.navigate(Screen.Legal.createRoute("privacy")) }
            )
        }

        // Permissions Setup Screen (for child devices to enable required permissions)
        composable(
            route = Screen.PermissionsSetup.route,
            arguments = listOf(
                navArgument("isFromSetupFlow") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val isFromSetupFlow = backStackEntry.arguments?.getBoolean("isFromSetupFlow") ?: false

            PermissionsSetupScreen(
                onNavigateBack = { navController.popBackStack() },
                onAllPermissionsGranted = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.PermissionsSetup.route) { inclusive = true }
                    }
                },
                isFromSetupFlow = isFromSetupFlow
            )
        }

        // Dashboard Screen
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToAlerts = {
                    navController.navigate(Screen.Alerts.route)
                },
                onNavigateToDevices = {
                    navController.navigate(Screen.Devices.route)
                },
                onNavigateToChildren = {
                    navController.navigate(Screen.Children.route)
                },
                onNavigateToScreenTimeLimits = { deviceId, deviceName ->
                    navController.navigate(Screen.ScreenTimeLimits.createRoute(deviceId, deviceName))
                },
                onNavigateToPermissionsSetup = {
                    navController.navigate(Screen.PermissionsSetup.createRoute(isFromSetupFlow = false))
                },
                onNavigateToLinkParent = {
                    navController.navigate(Screen.LinkParent.route)
                },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Link Parent Screen (child only) — shows a pairing code for a parent to enter
        composable(Screen.LinkParent.route) {
            LinkParentScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Settings Screen
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToWordList = { navController.navigate(Screen.WordList.route) },
                onNavigateToTextMonitoringSettings = { navController.navigate(Screen.TextMonitoringSettings.route) },
                onNavigateToPermissionsSetup = { navController.navigate(Screen.PermissionsSetup.createRoute(isFromSetupFlow = false)) },
                onNavigateToImageReview = { navController.navigate(Screen.ImageReview.route) },
                onNavigateToTextReview = { navController.navigate(Screen.TextReview.route) },
                onNavigateToChangePassword = { navController.navigate(Screen.ChangePassword.route) },
                onNavigateToPrivacyPolicy = { navController.navigate(Screen.Legal.createRoute("privacy")) },
                onNavigateToTerms = { navController.navigate(Screen.Legal.createRoute("terms")) },
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Legal document viewer (privacy policy / terms) — bundled offline HTML
        composable(
            route = Screen.Legal.route,
            arguments = listOf(navArgument("doc") { type = NavType.StringType })
        ) { backStackEntry ->
            val (title, assetDir) = when (backStackEntry.arguments?.getString("doc")) {
                "terms" -> stringResource(R.string.legal_terms_title) to "terms"
                else -> stringResource(R.string.legal_privacy_title) to "privacy-policy"
            }
            LegalDocScreen(
                title = title,
                assetDir = assetDir,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Change Password Screen
        composable(Screen.ChangePassword.route) {
            ChangePasswordScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Alerts Screen (with optional device filter)
        composable(
            route = Screen.Alerts.route,
            arguments = listOf(
                navArgument("deviceId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("deviceName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("alertId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val deviceIdStr = backStackEntry.arguments?.getString("deviceId")
            val deviceId = deviceIdStr?.toIntOrNull()
            val deviceName = backStackEntry.arguments?.getString("deviceName")?.let {
                if (it != "null") URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) else null
            }
            val highlightAlertId = backStackEntry.arguments?.getString("alertId")?.toIntOrNull()

            AlertsScreen(
                deviceId = deviceId,
                deviceName = deviceName,
                highlightAlertId = highlightAlertId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Devices Screen
        composable(Screen.Devices.route) {
            DevicesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToBlacklist = { deviceId, deviceName ->
                    navController.navigate(Screen.Blacklist.createRoute(deviceId, deviceName))
                },
                onNavigateToScreenTimeLimits = { deviceId, deviceName ->
                    navController.navigate(Screen.ScreenTimeLimits.createRoute(deviceId, deviceName))
                }
            )
        }

        // Children Screen (parent only)
        composable(Screen.Children.route) {
            ChildrenScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToChildDevices = { childId, childName ->
                    navController.navigate(Screen.ChildDevices.createRoute(childId, childName))
                }
            )
        }

        // Child's Devices Screen (parent viewing a specific child's devices)
        composable(
            route = Screen.ChildDevices.route,
            arguments = listOf(
                navArgument("childId") { type = NavType.IntType },
                navArgument("childName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val childId = backStackEntry.arguments?.getInt("childId") ?: return@composable
            val childName = backStackEntry.arguments?.getString("childName")?.let {
                URLDecoder.decode(it, StandardCharsets.UTF_8.toString())
            } ?: ""

            DevicesScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToBlacklist = { deviceId, deviceName ->
                    navController.navigate(Screen.Blacklist.createRoute(deviceId, deviceName))
                },
                onNavigateToScreenTimeLimits = { deviceId, deviceName ->
                    navController.navigate(Screen.ScreenTimeLimits.createRoute(deviceId, deviceName))
                },
                childId = childId,
                childName = childName
            )
        }

        // Blacklist Management Screen
        composable(
            route = Screen.Blacklist.route,
            arguments = listOf(
                navArgument("deviceId") { type = NavType.IntType },
                navArgument("deviceName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getInt("deviceId") ?: return@composable
            val deviceName = backStackEntry.arguments?.getString("deviceName")?.let {
                URLDecoder.decode(it, StandardCharsets.UTF_8.toString())
            } ?: ""

            BlacklistScreen(
                deviceId = deviceId,
                deviceName = deviceName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Custom Word List Management Screen
        composable(Screen.WordList.route) {
            WordListScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Text Monitoring Settings Screen
        composable(Screen.TextMonitoringSettings.route) {
            TextMonitoringSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToWordList = { navController.navigate(Screen.WordList.route) }
            )
        }

        // Screen Time Limits Management Screen
        composable(
            route = Screen.ScreenTimeLimits.route,
            arguments = listOf(
                navArgument("deviceId") { type = NavType.IntType },
                navArgument("deviceName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getInt("deviceId") ?: return@composable
            val deviceName = backStackEntry.arguments?.getString("deviceName")?.let {
                URLDecoder.decode(it, StandardCharsets.UTF_8.toString())
            } ?: ""

            ScreenTimeLimitsScreen(
                deviceId = deviceId,
                deviceName = deviceName,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Image Review Screen (for parents reviewing blurred images on child device)
        composable(Screen.ImageReview.route) {
            ImageReviewScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Text Review Screen (for parents reviewing flagged text on child device)
        composable(Screen.TextReview.route) {
            TextReviewScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
