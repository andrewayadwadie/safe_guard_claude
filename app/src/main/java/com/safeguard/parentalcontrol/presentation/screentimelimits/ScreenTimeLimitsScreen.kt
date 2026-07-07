package com.safeguard.parentalcontrol.presentation.screentimelimits

import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

import com.safeguard.parentalcontrol.presentation.theme.rememberScreenWidth
import com.safeguard.parentalcontrol.presentation.theme.responsiveContentWidth
import com.safeguard.parentalcontrol.presentation.theme.responsiveScreenPadding

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.presentation.designsystem.mirrorInRtl
import com.safeguard.parentalcontrol.util.formatAsHoursMinutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Data class representing an installed app
 */
private data class InstalledApp(
    val packageName: String,
    val appName: String,
    val icon: Drawable?
)

/**
 * Screen for managing screen time limits for a child device
 * Only accessible by parents
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTimeLimitsScreen(
    deviceId: Int,
    deviceName: String,
    onNavigateBack: () -> Unit,
    viewModel: ScreenTimeLimitsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Dialogs state
    var showAddAppLimitDialog by remember { mutableStateOf(false) }
    var showAddBlockedAppDialog by remember { mutableStateOf(false) }
    var showAddStudyTimeAppDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showTimePickerDialog by remember { mutableStateOf<TimePickerType?>(null) }

    // Load rules on first composition
    LaunchedEffect(deviceId) {
        viewModel.loadRules(deviceId)
    }

    // Show messages
    LaunchedEffect(uiState.error, uiState.successMessage) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Short
            )
            viewModel.clearMessages()
        }
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(
                message = it,
                duration = SnackbarDuration.Short
            )
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.devices_screen_time_limits))
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            modifier = Modifier.mirrorInRtl()
                        )
                    }
                },
                actions = {
                    // Show delete button only if rules exist (have been saved to server)
                    if (uiState.rules != null) {
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.screentime_cd_delete_rules))
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.saveRules() }
            ) {
                Icon(Icons.Default.Save, stringResource(R.string.common_save))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading && uiState.rules == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxHeight().responsiveContentWidth(rememberScreenWidth()),
                        contentPadding = PaddingValues(responsiveScreenPadding(rememberScreenWidth())),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Active toggle
                        item {
                            RulesActiveCard(
                                isActive = uiState.isActive,
                                onToggle = { viewModel.setRulesActive(it) }
                            )
                        }

                        // Daily Limit Section
                        item {
                            DailyLimitCard(
                                enabled = uiState.dailyLimitEnabled,
                                hours = uiState.dailyLimitHours,
                                minutes = uiState.dailyLimitMinutes,
                                onEnabledChange = { viewModel.setDailyLimitEnabled(it) },
                                onTimeClick = { showTimePickerDialog = TimePickerType.DAILY_LIMIT }
                            )
                        }

                        // Bedtime Section
                        item {
                            BedtimeCard(
                                enabled = uiState.bedtimeEnabled,
                                startTime = uiState.bedtimeStart,
                                endTime = uiState.bedtimeEnd,
                                onEnabledChange = { viewModel.setBedtimeEnabled(it) },
                                onStartTimeClick = { showTimePickerDialog = TimePickerType.BEDTIME_START },
                                onEndTimeClick = { showTimePickerDialog = TimePickerType.BEDTIME_END }
                            )
                        }

                        // Study Time Section
                        item {
                            StudyTimeCard(
                                enabled = uiState.studyTimeEnabled,
                                startTime = uiState.studyTimeStart,
                                endTime = uiState.studyTimeEnd,
                                onEnabledChange = { viewModel.setStudyTimeEnabled(it) },
                                onStartTimeClick = { showTimePickerDialog = TimePickerType.STUDY_TIME_START },
                                onEndTimeClick = { showTimePickerDialog = TimePickerType.STUDY_TIME_END }
                            )
                        }

                        // Study Time Allowed Apps Section (only show when study time is enabled)
                        if (uiState.studyTimeEnabled) {
                            item {
                                SectionHeader(
                                    title = stringResource(R.string.screentime_allowed_study),
                                    actionText = stringResource(R.string.screentime_add_app),
                                    onAction = { showAddStudyTimeAppDialog = true }
                                )
                            }

                            if (uiState.studyTimeAllowedApps.isEmpty()) {
                                item {
                                    EmptyListCard(
                                        icon = Icons.Default.School,
                                        message = stringResource(R.string.screentime_no_study_apps)
                                    )
                                }
                            } else {
                                items(
                                    items = uiState.studyTimeAllowedApps,
                                    key = { "study_$it" }
                                ) { packageName ->
                                    StudyTimeAllowedAppItem(
                                        packageName = packageName,
                                        onRemove = { viewModel.removeStudyTimeAllowedApp(packageName) }
                                    )
                                }
                            }
                        }

                        // Lock Phone Section
                        item {
                            LockPhoneCard(
                                isLocked = uiState.isDeviceLocked,
                                message = uiState.deviceLockedMessage,
                                onLockedChange = { viewModel.setDeviceLocked(it) },
                                onMessageChange = { viewModel.setDeviceLockedMessage(it) }
                            )
                        }

                        // Per-App Limits Section
                        item {
                            SectionHeader(
                                title = stringResource(R.string.screentime_per_app_limits),
                                actionText = stringResource(R.string.screentime_add_app),
                                onAction = { showAddAppLimitDialog = true }
                            )
                        }

                        if (uiState.appLimits.isEmpty()) {
                            item {
                                EmptyListCard(
                                    icon = Icons.Default.Timer,
                                    message = stringResource(R.string.screentime_no_app_limits)
                                )
                            }
                        } else {
                            items(
                                items = uiState.appLimits.entries.toList(),
                                key = { "limit_${it.key}" }
                            ) { (packageName, limitSeconds) ->
                                AppLimitItem(
                                    packageName = packageName,
                                    limitSeconds = limitSeconds,
                                    onRemove = { viewModel.removeAppLimit(packageName) }
                                )
                            }
                        }

                        // Blocked Apps Section
                        item {
                            SectionHeader(
                                title = stringResource(R.string.screentime_blocked_apps),
                                actionText = stringResource(R.string.screentime_block_app),
                                onAction = { showAddBlockedAppDialog = true }
                            )
                        }

                        if (uiState.blockedApps.isEmpty()) {
                            item {
                                EmptyListCard(
                                    icon = Icons.Default.Block,
                                    message = stringResource(R.string.screentime_no_blocked_apps)
                                )
                            }
                        } else {
                            items(
                                items = uiState.blockedApps,
                                key = { "blocked_$it" }
                            ) { packageName ->
                                BlockedAppItem(
                                    packageName = packageName,
                                    onRemove = { viewModel.removeBlockedApp(packageName) }
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(72.dp)) // FAB clearance
                        }
                    }
                }
            }

            // Show loading overlay when saving
            if (uiState.isLoading && uiState.rules != null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }

    // Add App Limit Dialog
    if (showAddAppLimitDialog) {
        AddAppLimitDialog(
            onDismiss = { showAddAppLimitDialog = false },
            onConfirm = { packageName, limitSeconds ->
                viewModel.addAppLimit(packageName, limitSeconds)
                showAddAppLimitDialog = false
            },
            childApps = uiState.childApps,
            isLoadingApps = uiState.isLoadingApps,
            existingPackages = uiState.appLimits.keys
        )
    }

    // Add Blocked App Dialog
    if (showAddBlockedAppDialog) {
        AddBlockedAppDialog(
            onDismiss = { showAddBlockedAppDialog = false },
            onConfirm = { packageName ->
                viewModel.addBlockedApp(packageName)
                showAddBlockedAppDialog = false
            },
            childApps = uiState.childApps,
            isLoadingApps = uiState.isLoadingApps,
            existingPackages = uiState.blockedApps.toSet()
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.screentime_delete_all_title)) },
            text = {
                Text(stringResource(R.string.screentime_delete_all_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRules()
                        showDeleteConfirmDialog = false
                    }
                ) {
                    Text(
                        stringResource(R.string.screentime_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Time Picker Dialog
    showTimePickerDialog?.let { type ->
        TimePickerDialogWrapper(
            initialHour = when (type) {
                TimePickerType.DAILY_LIMIT -> uiState.dailyLimitHours
                TimePickerType.BEDTIME_START -> uiState.bedtimeStart.split(":").getOrNull(0)?.toIntOrNull() ?: 21
                TimePickerType.BEDTIME_END -> uiState.bedtimeEnd.split(":").getOrNull(0)?.toIntOrNull() ?: 7
                TimePickerType.STUDY_TIME_START -> uiState.studyTimeStart.split(":").getOrNull(0)?.toIntOrNull() ?: 14
                TimePickerType.STUDY_TIME_END -> uiState.studyTimeEnd.split(":").getOrNull(0)?.toIntOrNull() ?: 17
            },
            initialMinute = when (type) {
                TimePickerType.DAILY_LIMIT -> uiState.dailyLimitMinutes
                TimePickerType.BEDTIME_START -> uiState.bedtimeStart.split(":").getOrNull(1)?.toIntOrNull() ?: 0
                TimePickerType.BEDTIME_END -> uiState.bedtimeEnd.split(":").getOrNull(1)?.toIntOrNull() ?: 0
                TimePickerType.STUDY_TIME_START -> uiState.studyTimeStart.split(":").getOrNull(1)?.toIntOrNull() ?: 0
                TimePickerType.STUDY_TIME_END -> uiState.studyTimeEnd.split(":").getOrNull(1)?.toIntOrNull() ?: 0
            },
            onDismiss = { showTimePickerDialog = null },
            onConfirm = { hour, minute ->
                when (type) {
                    TimePickerType.DAILY_LIMIT -> viewModel.setDailyLimit(hour, minute)
                    TimePickerType.BEDTIME_START -> viewModel.setBedtimeStart(String.format("%02d:%02d", hour, minute))
                    TimePickerType.BEDTIME_END -> viewModel.setBedtimeEnd(String.format("%02d:%02d", hour, minute))
                    TimePickerType.STUDY_TIME_START -> viewModel.setStudyTimeStart(String.format("%02d:%02d", hour, minute))
                    TimePickerType.STUDY_TIME_END -> viewModel.setStudyTimeEnd(String.format("%02d:%02d", hour, minute))
                }
                showTimePickerDialog = null
            }
        )
    }

    // Add Study Time Allowed App Dialog
    if (showAddStudyTimeAppDialog) {
        AddStudyTimeAppDialog(
            onDismiss = { showAddStudyTimeAppDialog = false },
            onConfirm = { packageName ->
                viewModel.addStudyTimeAllowedApp(packageName)
                showAddStudyTimeAppDialog = false
            },
            childApps = uiState.childApps,
            isLoadingApps = uiState.isLoadingApps,
            existingPackages = uiState.studyTimeAllowedApps.toSet()
        )
    }
}

private enum class TimePickerType {
    DAILY_LIMIT,
    BEDTIME_START,
    BEDTIME_END,
    STUDY_TIME_START,
    STUDY_TIME_END
}

@Composable
private fun RulesActiveCard(
    isActive: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isActive) Icons.Default.Shield else Icons.Default.ShieldMoon,
                    contentDescription = null,
                    tint = if (isActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isActive) stringResource(R.string.screentime_rules_active) else stringResource(R.string.screentime_rules_disabled),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isActive) stringResource(R.string.screentime_rules_enforced) else stringResource(R.string.screentime_no_restrictions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = isActive,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
private fun DailyLimitCard(
    enabled: Boolean,
    hours: Int,
    minutes: Int,
    onEnabledChange: (Boolean) -> Unit,
    onTimeClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.screentime_daily_limit),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onTimeClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Timer, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hours > 0 || minutes > 0) {
                            "${hours}h ${minutes}m per day"
                        } else {
                            stringResource(R.string.screentime_set_daily_limit)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BedtimeCard(
    enabled: Boolean,
    startTime: String,
    endTime: String,
    onEnabledChange: (Boolean) -> Unit,
    onStartTimeClick: () -> Unit,
    onEndTimeClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Bedtime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.screentime_bedtime_mode),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(R.string.screentime_bedtime_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onStartTimeClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.screentime_start), style = MaterialTheme.typography.labelSmall)
                            Text(startTime)
                        }
                    }
                    OutlinedButton(
                        onClick = onEndTimeClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.screentime_end), style = MaterialTheme.typography.labelSmall)
                            Text(endTime)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyTimeCard(
    enabled: Boolean,
    startTime: String,
    endTime: String,
    onEnabledChange: (Boolean) -> Unit,
    onStartTimeClick: () -> Unit,
    onEndTimeClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.School,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.screentime_study_time),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(R.string.screentime_study_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onStartTimeClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.screentime_start), style = MaterialTheme.typography.labelSmall)
                            Text(startTime)
                        }
                    }
                    OutlinedButton(
                        onClick = onEndTimeClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.screentime_end), style = MaterialTheme.typography.labelSmall)
                            Text(endTime)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LockPhoneCard(
    isLocked: Boolean,
    message: String,
    onLockedChange: (Boolean) -> Unit,
    onMessageChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLocked) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            if (isLocked) stringResource(R.string.screentime_device_locked) else stringResource(R.string.screentime_lock_device),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (isLocked) stringResource(R.string.screentime_locked_desc) else stringResource(R.string.screentime_lock_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = isLocked,
                    onCheckedChange = onLockedChange
                )
            }

            if (isLocked) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = message,
                    onValueChange = onMessageChange,
                    label = { Text(stringResource(R.string.screentime_message_child)) },
                    placeholder = { Text(stringResource(R.string.screentime_message_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun StudyTimeAllowedAppItem(
    packageName: String,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val appInfo = remember(packageName) {
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            InstalledApp(
                packageName = packageName,
                appName = pm.getApplicationLabel(info).toString(),
                icon = pm.getApplicationIcon(info)
            )
        } catch (e: Exception) {
            InstalledApp(
                packageName = packageName,
                appName = packageName.split(".").lastOrNull() ?: packageName,
                icon = null
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box {
                    AppIcon(icon = appInfo.icon, size = 40)
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.BottomEnd)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appInfo.appName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.screentime_allowed_during_study),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.screentime_cd_remove),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    actionText: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        TextButton(onClick = onAction) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(actionText)
        }
    }
}

@Composable
private fun EmptyListCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppLimitItem(
    packageName: String,
    limitSeconds: Int,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val appInfo = remember(packageName) {
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            InstalledApp(
                packageName = packageName,
                appName = pm.getApplicationLabel(info).toString(),
                icon = pm.getApplicationIcon(info)
            )
        } catch (e: Exception) {
            InstalledApp(
                packageName = packageName,
                appName = packageName.split(".").lastOrNull() ?: packageName,
                icon = null
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                AppIcon(icon = appInfo.icon, size = 40)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appInfo.appName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = limitSeconds.formatAsHoursMinutes(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.screentime_cd_remove),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun BlockedAppItem(
    packageName: String,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val appInfo = remember(packageName) {
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            InstalledApp(
                packageName = packageName,
                appName = pm.getApplicationLabel(info).toString(),
                icon = pm.getApplicationIcon(info)
            )
        } catch (e: Exception) {
            InstalledApp(
                packageName = packageName,
                appName = packageName.split(".").lastOrNull() ?: packageName,
                icon = null
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box {
                    AppIcon(icon = appInfo.icon, size = 40)
                    // Block overlay icon
                    Icon(
                        Icons.Default.Block,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.BottomEnd)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appInfo.appName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.screentime_cd_remove),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Get list of installed user apps (excluding system apps)
 */
@Composable
private fun rememberInstalledApps(excludePackages: Set<String> = emptySet()): List<InstalledApp> {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }

    LaunchedEffect(excludePackages) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { appInfo ->
                    // Include user-installed apps and some common system apps
                    val isUserApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                    val isUpdatedSystemApp = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    val hasLaunchIntent = pm.getLaunchIntentForPackage(appInfo.packageName) != null

                    (isUserApp || isUpdatedSystemApp || hasLaunchIntent) &&
                        appInfo.packageName != context.packageName &&
                        appInfo.packageName !in excludePackages
                }
                .map { appInfo ->
                    InstalledApp(
                        packageName = appInfo.packageName,
                        appName = pm.getApplicationLabel(appInfo).toString(),
                        icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
                    )
                }
                .sortedBy { it.appName.lowercase() }
        }
    }

    return apps
}

@Composable
private fun AddAppLimitDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit,
    childApps: List<ChildApp>,
    isLoadingApps: Boolean,
    existingPackages: Set<String> = emptySet()
) {
    var selectedApp by remember { mutableStateOf<ChildApp?>(null) }
    var hours by remember { mutableStateOf("1") }
    var minutes by remember { mutableStateOf("0") }
    var searchQuery by remember { mutableStateOf("") }

    // Filter out already added apps and apply search
    val availableApps = remember(childApps, existingPackages) {
        childApps.filter { it.packageName !in existingPackages }
    }
    val filteredApps = remember(availableApps, searchQuery) {
        if (searchQuery.isBlank()) availableApps
        else availableApps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    val hasValidTime = (hours.toIntOrNull() ?: 0) > 0 || (minutes.toIntOrNull() ?: 0) > 0
    val canSubmit = selectedApp != null && hasValidTime

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Timer, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.screentime_add_app_limit))
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Selected app display
                if (selectedApp != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Android,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    selectedApp!!.appName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    selectedApp!!.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(onClick = { selectedApp = null }) {
                                Icon(Icons.Default.Close, stringResource(R.string.screentime_cd_remove_selection))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Time limit input
                    Text(stringResource(R.string.screentime_daily_limit_label), style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = hours,
                            onValueChange = { if (it.all { c -> c.isDigit() }) hours = it.take(2) },
                            label = { Text(stringResource(R.string.screentime_hours)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minutes,
                            onValueChange = { if (it.all { c -> c.isDigit() }) minutes = it.take(2) },
                            label = { Text(stringResource(R.string.screentime_minutes)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    // Search field
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(stringResource(R.string.screentime_search_apps)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // App list
                    when {
                        isLoadingApps -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        stringResource(R.string.screentime_loading_apps),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        availableApps.isEmpty() -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        stringResource(R.string.screentime_no_usage_data),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp)
                            ) {
                                itemsIndexed(filteredApps, key = { index, app -> "limit_${index}_${app.packageName}" }) { _, app ->
                                    ChildAppListItem(
                                        app = app,
                                        onClick = { selectedApp = app }
                                    )
                                }

                                if (filteredApps.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                stringResource(R.string.screentime_no_matching_apps),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedApp?.let { app ->
                        val hoursInt = hours.toIntOrNull() ?: 0
                        val minutesInt = minutes.toIntOrNull() ?: 0
                        val totalSeconds = hoursInt * 3600 + minutesInt * 60
                        if (totalSeconds > 0) {
                            onConfirm(app.packageName, totalSeconds)
                        }
                    }
                },
                enabled = canSubmit
            ) {
                Text(stringResource(R.string.screentime_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun ChildAppListItem(
    app: ChildApp,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Android,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    Divider()
}

@Composable
private fun AppIcon(icon: Drawable?, size: Int = 40) {
    if (icon != null) {
        Image(
            bitmap = icon.toBitmap(size, size).asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size.dp)
        )
    } else {
        Surface(
            modifier = Modifier.size(size.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Android,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun AppListItem(
    app: InstalledApp,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(icon = app.icon, size = 40)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    Divider()
}

@Composable
private fun AddBlockedAppDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    childApps: List<ChildApp>,
    isLoadingApps: Boolean,
    existingPackages: Set<String> = emptySet()
) {
    var selectedApp by remember { mutableStateOf<ChildApp?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Filter out already blocked apps and apply search
    val availableApps = remember(childApps, existingPackages) {
        childApps.filter { it.packageName !in existingPackages }
    }
    val filteredApps = remember(availableApps, searchQuery) {
        if (searchQuery.isBlank()) availableApps
        else availableApps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Block, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.screentime_block_app))
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Selected app display
                if (selectedApp != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Android,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    selectedApp!!.appName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    selectedApp!!.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(onClick = { selectedApp = null }) {
                                Icon(Icons.Default.Close, stringResource(R.string.screentime_cd_remove_selection))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        stringResource(R.string.screentime_block_explain),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    // Search field
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(stringResource(R.string.screentime_search_apps)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // App list
                    when {
                        isLoadingApps -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        stringResource(R.string.screentime_loading_apps),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        availableApps.isEmpty() -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        stringResource(R.string.screentime_no_usage_data),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp)
                            ) {
                                itemsIndexed(filteredApps, key = { index, app -> "block_${index}_${app.packageName}" }) { _, app ->
                                    ChildAppListItem(
                                        app = app,
                                        onClick = { selectedApp = app }
                                    )
                                }

                                if (filteredApps.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                stringResource(R.string.screentime_no_matching_apps),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedApp?.let { app ->
                        onConfirm(app.packageName)
                    }
                },
                enabled = selectedApp != null
            ) {
                Text(stringResource(R.string.screentime_block))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun AddStudyTimeAppDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    childApps: List<ChildApp>,
    isLoadingApps: Boolean,
    existingPackages: Set<String> = emptySet()
) {
    var selectedApp by remember { mutableStateOf<ChildApp?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Filter out already added apps and apply search
    val availableApps = remember(childApps, existingPackages) {
        childApps.filter { it.packageName !in existingPackages }
    }
    val filteredApps = remember(availableApps, searchQuery) {
        if (searchQuery.isBlank()) availableApps
        else availableApps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.School, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.screentime_allow_study_title))
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Selected app display
                if (selectedApp != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Android,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    selectedApp!!.appName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    selectedApp!!.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            IconButton(onClick = { selectedApp = null }) {
                                Icon(Icons.Default.Close, stringResource(R.string.screentime_cd_remove_selection))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        stringResource(R.string.screentime_allow_explain),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    // Search field
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(stringResource(R.string.screentime_search_apps)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // App list
                    when {
                        isLoadingApps -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator()
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        stringResource(R.string.screentime_loading_apps),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        availableApps.isEmpty() -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        stringResource(R.string.screentime_no_usage_data),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp)
                            ) {
                                itemsIndexed(filteredApps, key = { index, app -> "study_${index}_${app.packageName}" }) { _, app ->
                                    ChildAppListItem(
                                        app = app,
                                        onClick = { selectedApp = app }
                                    )
                                }

                                if (filteredApps.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                stringResource(R.string.screentime_no_matching_apps),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    selectedApp?.let { app ->
                        onConfirm(app.packageName)
                    }
                },
                enabled = selectedApp != null
            ) {
                Text(stringResource(R.string.screentime_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialogWrapper(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screentime_select_time)) },
        text = {
            TimePicker(state = timePickerState)
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(timePickerState.hour, timePickerState.minute)
                }
            ) {
                Text(stringResource(R.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun ScreenTimeLimitsPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DailyLimitCard(true, 2, 30, {}, {})
        }
    }
}

@Preview(name = "ScreenTime · Light", showBackground = true)
@Composable private fun ScreenTimeLimitsLightPreview() { SafeGuardTheme(darkTheme = false) { ScreenTimeLimitsPreviewContent() } }
@Preview(name = "ScreenTime · Dark", showBackground = true)
@Composable private fun ScreenTimeLimitsDarkPreview() { SafeGuardTheme(darkTheme = true) { ScreenTimeLimitsPreviewContent() } }
