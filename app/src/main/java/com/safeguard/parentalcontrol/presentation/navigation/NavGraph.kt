package com.safeguard.parentalcontrol.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.safeguard.parentalcontrol.presentation.alerts.AlertsScreen
import com.safeguard.parentalcontrol.presentation.auth.LoginScreen
import com.safeguard.parentalcontrol.presentation.auth.RegisterScreen
import com.safeguard.parentalcontrol.presentation.blacklist.BlacklistScreen
import com.safeguard.parentalcontrol.presentation.children.ChildrenScreen
import com.safeguard.parentalcontrol.presentation.dashboard.DashboardScreen
import com.safeguard.parentalcontrol.presentation.devices.DevicesScreen
import com.safeguard.parentalcontrol.presentation.devicesetup.DeviceSetupScreen
import com.safeguard.parentalcontrol.presentation.imagereview.ImageReviewScreen
import com.safeguard.parentalcontrol.presentation.screentimelimits.ScreenTimeLimitsScreen
import com.safeguard.parentalcontrol.presentation.settings.SettingsScreen
import com.safeguard.parentalcontrol.presentation.settings.TextMonitoringSettingsScreen
import com.safeguard.parentalcontrol.presentation.setup.PermissionsSetupScreen
import com.safeguard.parentalcontrol.presentation.wordlist.WordListScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Navigation routes
 */
sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object Dashboard : Screen("dashboard")
    data object DeviceSetup : Screen("device_setup")
    data object PermissionsSetup : Screen("permissions_setup/{isFromSetupFlow}") {
        fun createRoute(isFromSetupFlow: Boolean = false): String = "permissions_setup/$isFromSetupFlow"
    }
    data object Settings : Screen("settings")
    data object Alerts : Screen("alerts?deviceId={deviceId}&deviceName={deviceName}") {
        fun createRoute(deviceId: Int? = null, deviceName: String? = null): String {
            return if (deviceId != null && deviceName != null) {
                val encodedName = URLEncoder.encode(deviceName, StandardCharsets.UTF_8.toString())
                "alerts?deviceId=$deviceId&deviceName=$encodedName"
            } else {
                "alerts"
            }
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
    startDestination: String = Screen.Login.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        // Login Screen
        composable(Screen.Login.route) {
            LoginScreen(
                onNavigateToRegister = {
                    navController.navigate(Screen.Register.route)
                },
                onLoginSuccess = {
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
                    // After device setup, go to permissions setup for child devices
                    navController.navigate(Screen.PermissionsSetup.createRoute(isFromSetupFlow = true)) {
                        popUpTo(Screen.DeviceSetup.route) { inclusive = true }
                    }
                }
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
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
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
                onLogout = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
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
                }
            )
        ) { backStackEntry ->
            val deviceIdStr = backStackEntry.arguments?.getString("deviceId")
            val deviceId = deviceIdStr?.toIntOrNull()
            val deviceName = backStackEntry.arguments?.getString("deviceName")?.let {
                if (it != "null") URLDecoder.decode(it, StandardCharsets.UTF_8.toString()) else null
            }

            AlertsScreen(
                deviceId = deviceId,
                deviceName = deviceName,
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
    }
}
