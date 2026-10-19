package com.safeguard.parentalcontrol.presentation.screentimelimits

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.ScreenTimeRule
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.ScreenTimeRepository
import com.safeguard.parentalcontrol.data.repository.ScreenTimeRulesRepository
import com.safeguard.parentalcontrol.util.AppLockPipe
import com.safeguard.parentalcontrol.util.LocaleHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/**
 * Represents an app installed on the child's device
 */
data class ChildApp(
    val packageName: String,
    val appName: String
)

/**
 * One control on this screen, as the unit a save is scoped to.
 *
 * A save carries only the field(s) of the control the parent touched, so two parents editing
 * different controls at the same time cannot clobber each other and a lock change can never
 * overwrite a bedtime with this screen's stale copy of it.
 */
enum class RuleField {
    DAILY_LIMIT,
    BEDTIME,
    STUDY_TIME,
    STUDY_TIME_APPS,
    DEVICE_LOCK,
    APP_LIMITS,
    BLOCKED_APPS,
    ACTIVE
}

/**
 * The last values the server confirmed, per control.
 *
 * A failed save restores only its own field from here, which is why a rollback cannot discard
 * an unrelated change the parent made while the request was in flight.
 */
data class ConfirmedRuleValues(
    val dailyLimitEnabled: Boolean = false,
    val dailyLimitHours: Int = 2,
    val dailyLimitMinutes: Int = 0,
    val bedtimeEnabled: Boolean = false,
    val bedtimeStart: String = "21:00",
    val bedtimeEnd: String = "07:00",
    val studyTimeEnabled: Boolean = false,
    val studyTimeStart: String = "14:00",
    val studyTimeEnd: String = "17:00",
    val studyTimeAllowedApps: List<String> = emptyList(),
    val isDeviceLocked: Boolean = false,
    val deviceLockedMessage: String = "",
    val appLimits: Map<String, Int> = emptyMap(),
    val blockedApps: List<String> = emptyList(),
    val isActive: Boolean = true
)

/**
 * UI State for screen time limits management screen
 */
data class ScreenTimeLimitsUiState(
    /** Initial screen load only. A control saving never blocks the rest of the screen. */
    val isLoading: Boolean = false,
    val rules: ScreenTimeRule? = null,
    val dailyLimitEnabled: Boolean = false,
    val dailyLimitHours: Int = 2,
    val dailyLimitMinutes: Int = 0,
    val bedtimeEnabled: Boolean = false,
    val bedtimeStart: String = "21:00",
    val bedtimeEnd: String = "07:00",
    // Study Time
    val studyTimeEnabled: Boolean = false,
    val studyTimeStart: String = "14:00",
    val studyTimeEnd: String = "17:00",
    val studyTimeAllowedApps: List<String> = emptyList(),
    // Remote Lock
    val isDeviceLocked: Boolean = false,
    val deviceLockedMessage: String = "",
    val appLimits: Map<String, Int> = emptyMap(),
    val blockedApps: List<String> = emptyList(),
    val isActive: Boolean = true,
    val childApps: List<ChildApp> = emptyList(),
    val isLoadingApps: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    /** Controls with a save in flight, so each row can show its own progress. */
    val savingFields: Set<RuleField> = emptySet(),
    /** What the server has confirmed; a failed save reverts its own field to this. */
    val confirmedState: ConfirmedRuleValues = ConfirmedRuleValues(),
    /** Child-device enforcement problem worth telling the parent about, if any. */
    val enforcementWarning: String? = null
)

/**
 * ViewModel for managing screen time limits
 */
@HiltViewModel
class ScreenTimeLimitsViewModel @Inject constructor(
    private val screenTimeRulesRepository: ScreenTimeRulesRepository,
    private val screenTimeRepository: ScreenTimeRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScreenTimeLimitsUiState())
    val uiState: StateFlow<ScreenTimeLimitsUiState> = _uiState.asStateFlow()

    private var currentDeviceId: Int = -1

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(context).getString(resId, *args)

    /**
     * Load screen time rules for a device
     */
    fun loadRules(deviceId: Int) {
        if (deviceId <= 0) {
            _uiState.update { it.copy(error = getString(R.string.screentime_error_invalid_device)) }
            return
        }

        currentDeviceId = deviceId
        AppLockPipe.d("LOAD requested deviceId=$deviceId")

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Load rules and child apps in parallel
            launch { loadChildApps(deviceId) }

            AppLockPipe.d("LOAD fetching rules deviceId=$deviceId")
            when (val result = screenTimeRulesRepository.getScreenTimeRules(deviceId)) {
                is NetworkResult.Success -> {
                    val rules = result.data
                    if (rules != null) {
                        val dailyLimitSeconds = rules.dailyLimit ?: 0
                        val dailyLimitEnabled = rules.dailyLimit != null && rules.dailyLimit > 0

                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                rules = rules,
                                dailyLimitEnabled = dailyLimitEnabled,
                                dailyLimitHours = dailyLimitSeconds / 3600,
                                dailyLimitMinutes = (dailyLimitSeconds % 3600) / 60,
                                bedtimeEnabled = rules.bedtimeEnabled,
                                bedtimeStart = rules.bedtimeStart ?: "21:00",
                                bedtimeEnd = rules.bedtimeEnd ?: "07:00",
                                studyTimeEnabled = rules.studyTimeEnabled,
                                studyTimeStart = rules.studyTimeStart ?: "14:00",
                                studyTimeEnd = rules.studyTimeEnd ?: "17:00",
                                studyTimeAllowedApps = rules.studyTimeAllowedApps ?: emptyList(),
                                isDeviceLocked = rules.isDeviceLocked,
                                deviceLockedMessage = rules.deviceLockedMessage ?: "",
                                appLimits = rules.appLimits ?: emptyMap(),
                                blockedApps = rules.blockedApps ?: emptyList(),
                                isActive = rules.isActive
                            )
                        }
                        // Everything just loaded is, by definition, server-confirmed.
                        _uiState.update { it.copy(confirmedState = it.captureAll()) }
                        AppLockPipe.i(
                            "LOAD deviceId=$deviceId isActive=${rules.isActive} " +
                                "blocked=${rules.blockedApps?.size ?: 0} " +
                                "appLimits=${rules.appLimits?.size ?: 0}"
                        )
                    } else {
                        // No rules configured yet - show default empty state
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                rules = null,
                                dailyLimitEnabled = false,
                                dailyLimitHours = 2,
                                dailyLimitMinutes = 0,
                                bedtimeEnabled = false,
                                bedtimeStart = "21:00",
                                bedtimeEnd = "07:00",
                                studyTimeEnabled = false,
                                studyTimeStart = "14:00",
                                studyTimeEnd = "17:00",
                                studyTimeAllowedApps = emptyList(),
                                isDeviceLocked = false,
                                deviceLockedMessage = "",
                                appLimits = emptyMap(),
                                blockedApps = emptyList(),
                                isActive = true
                            )
                        }
                        _uiState.update { it.copy(confirmedState = it.captureAll()) }
                        AppLockPipe.i("LOAD deviceId=$deviceId no rules configured yet")
                    }
                }
                is NetworkResult.Error -> {
                    // Check if this is a 404 "no rules configured" - not a real error
                    if (result.message.contains("not found", ignoreCase = true) ||
                        result.message.contains("No screen time rules", ignoreCase = true) ||
                        result.code == 404) {
                        // No rules exist yet - show default empty state (not an error)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                rules = null,
                                error = null
                            )
                        }
                    } else {
                        // Actual error
                        _uiState.update {
                            it.copy(isLoading = false, error = result.message)
                        }
                    }
                }
                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    /**
     * Load apps from child's device via backend
     * Fetches app usage logs which contain all apps the child has used
     */
    private suspend fun loadChildApps(deviceId: Int) {
        _uiState.update { it.copy(isLoadingApps = true) }

        when (val result = screenTimeRepository.getAppUsageLogs(deviceId, days = 30)) {
            is NetworkResult.Success -> {
                // Extract unique apps from usage logs
                val apps = result.data
                    .distinctBy { it.packageName }
                    .map { log ->
                        ChildApp(
                            packageName = log.packageName,
                            appName = log.appName ?: log.packageName.split(".").lastOrNull() ?: log.packageName
                        )
                    }
                    .sortedBy { it.appName.lowercase() }

                _uiState.update {
                    it.copy(childApps = apps, isLoadingApps = false)
                }
            }
            is NetworkResult.Error -> {
                _uiState.update { it.copy(isLoadingApps = false) }
            }
            is NetworkResult.Loading -> {
                // Already handled
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Per-control saving
    //
    // Every control on this screen saves its own change the moment it is made. There is no
    // save button: the previous one was an unlabeled FAB, and a parent who never pressed it
    // had a screen that showed a lock the server had never been told about.
    //
    // Three properties the mechanism has to hold:
    //   - one request carries one control's fields, never the whole rule row, so a lock change
    //     cannot overwrite a bedtime with this screen's stale copy of it;
    //   - rapid changes to one control coalesce to the settled value, so a triple tap is one
    //     request, not three;
    //   - a failure reverts that control and says so, so the switch can never quietly disagree
    //     with the server.
    // ---------------------------------------------------------------------------------------

    /** Long enough to swallow a double tap, short enough to feel immediate. */
    private val saveDebounceMs = 250L

    /** One in-flight job per control: a newer change cancels the pending older one. */
    private val saveJobs = mutableMapOf<RuleField, Job>()

    /**
     * At most one PUT in flight for this screen. Serialising the writes is what keeps a slow
     * response for a superseded value from landing after the newer one and winning.
     */
    private val saveMutex = Mutex()

    /** Snapshot of the whole screen as server-confirmed. Used when rules are (re)loaded. */
    private fun ScreenTimeLimitsUiState.captureAll() = ConfirmedRuleValues(
        dailyLimitEnabled = dailyLimitEnabled,
        dailyLimitHours = dailyLimitHours,
        dailyLimitMinutes = dailyLimitMinutes,
        bedtimeEnabled = bedtimeEnabled,
        bedtimeStart = bedtimeStart,
        bedtimeEnd = bedtimeEnd,
        studyTimeEnabled = studyTimeEnabled,
        studyTimeStart = studyTimeStart,
        studyTimeEnd = studyTimeEnd,
        studyTimeAllowedApps = studyTimeAllowedApps,
        isDeviceLocked = isDeviceLocked,
        deviceLockedMessage = deviceLockedMessage,
        appLimits = appLimits,
        blockedApps = blockedApps,
        isActive = isActive
    )

    /** Promote one control's current values to confirmed, leaving every other control alone. */
    private fun ConfirmedRuleValues.capturing(
        field: RuleField,
        state: ScreenTimeLimitsUiState
    ): ConfirmedRuleValues = when (field) {
        RuleField.DAILY_LIMIT -> copy(
            dailyLimitEnabled = state.dailyLimitEnabled,
            dailyLimitHours = state.dailyLimitHours,
            dailyLimitMinutes = state.dailyLimitMinutes
        )
        RuleField.BEDTIME -> copy(
            bedtimeEnabled = state.bedtimeEnabled,
            bedtimeStart = state.bedtimeStart,
            bedtimeEnd = state.bedtimeEnd
        )
        RuleField.STUDY_TIME -> copy(
            studyTimeEnabled = state.studyTimeEnabled,
            studyTimeStart = state.studyTimeStart,
            studyTimeEnd = state.studyTimeEnd
        )
        RuleField.STUDY_TIME_APPS -> copy(studyTimeAllowedApps = state.studyTimeAllowedApps)
        RuleField.DEVICE_LOCK -> copy(
            isDeviceLocked = state.isDeviceLocked,
            deviceLockedMessage = state.deviceLockedMessage
        )
        RuleField.APP_LIMITS -> copy(appLimits = state.appLimits)
        RuleField.BLOCKED_APPS -> copy(blockedApps = state.blockedApps)
        RuleField.ACTIVE -> copy(isActive = state.isActive)
    }

    /** Revert one control to its last confirmed values, leaving every other control alone. */
    private fun ScreenTimeLimitsUiState.revert(field: RuleField): ScreenTimeLimitsUiState {
        val c = confirmedState
        return when (field) {
            RuleField.DAILY_LIMIT -> copy(
                dailyLimitEnabled = c.dailyLimitEnabled,
                dailyLimitHours = c.dailyLimitHours,
                dailyLimitMinutes = c.dailyLimitMinutes
            )
            RuleField.BEDTIME -> copy(
                bedtimeEnabled = c.bedtimeEnabled,
                bedtimeStart = c.bedtimeStart,
                bedtimeEnd = c.bedtimeEnd
            )
            RuleField.STUDY_TIME -> copy(
                studyTimeEnabled = c.studyTimeEnabled,
                studyTimeStart = c.studyTimeStart,
                studyTimeEnd = c.studyTimeEnd
            )
            RuleField.STUDY_TIME_APPS -> copy(studyTimeAllowedApps = c.studyTimeAllowedApps)
            RuleField.DEVICE_LOCK -> copy(
                isDeviceLocked = c.isDeviceLocked,
                deviceLockedMessage = c.deviceLockedMessage
            )
            RuleField.APP_LIMITS -> copy(appLimits = c.appLimits)
            RuleField.BLOCKED_APPS -> copy(blockedApps = c.blockedApps)
            RuleField.ACTIVE -> copy(isActive = c.isActive)
        }
    }

    /**
     * Apply a control's change on screen at once, then persist just that control.
     *
     * [mutate] is the optimistic UI update, applied immediately so the switch moves under the
     * parent's finger. [request] builds the PUT from the settled state after the debounce, so
     * it sees the value the parent actually ended on.
     */
    private fun saveField(
        field: RuleField,
        mutate: (ScreenTimeLimitsUiState) -> ScreenTimeLimitsUiState,
        request: suspend (ScreenTimeLimitsUiState) -> NetworkResult<ScreenTimeRule>
    ) {
        if (currentDeviceId <= 0) {
            AppLockPipe.e("SAVE field=$field rejected: no device id loaded")
            _uiState.update { it.copy(error = getString(R.string.screentime_error_invalid_device)) }
            return
        }

        _uiState.update(mutate)

        saveJobs[field]?.cancel()
        saveJobs[field] = viewModelScope.launch {
            delay(saveDebounceMs)

            val settled = _uiState.value
            _uiState.update { it.copy(savingFields = it.savingFields + field, error = null) }
            AppLockPipe.i("SAVE field=$field deviceId=$currentDeviceId ${settled.describe(field)}")

            val result = try {
                saveMutex.withLock { request(settled) }
            } finally {
                _uiState.update { it.copy(savingFields = it.savingFields - field) }
            }

            when (result) {
                is NetworkResult.Success -> {
                    AppLockPipe.i(
                        "SAVE field=$field http=200 confirmed ${settled.describe(field)}"
                    )
                    _uiState.update {
                        it.copy(
                            rules = result.data,
                            confirmedState = it.confirmedState.capturing(field, settled)
                        )
                    }
                }
                is NetworkResult.Error -> {
                    val reverted = _uiState.value.revert(field)
                    AppLockPipe.w(
                        "SAVE field=$field failed http=${result.code ?: -1} " +
                            "-> rollback ${reverted.describe(field)}"
                    )
                    _uiState.update { it.revert(field).copy(error = result.message) }
                }
                is NetworkResult.Loading -> {
                    // safeApiCall never terminates with Loading; nothing to confirm or revert.
                }
            }
        }
    }

    /**
     * What a save carries, for the log. Package names, sizes and flags only — never a token,
     * an email address or monitored content (see [AppLockPipe]).
     */
    private fun ScreenTimeLimitsUiState.describe(field: RuleField): String = when (field) {
        RuleField.DAILY_LIMIT -> "enabled=$dailyLimitEnabled seconds=${dailyLimitSeconds()}"
        RuleField.BEDTIME -> "enabled=$bedtimeEnabled window=$bedtimeStart-$bedtimeEnd"
        RuleField.STUDY_TIME -> "enabled=$studyTimeEnabled window=$studyTimeStart-$studyTimeEnd"
        RuleField.STUDY_TIME_APPS -> "size=${studyTimeAllowedApps.size}"
        RuleField.DEVICE_LOCK -> "locked=$isDeviceLocked messageLen=${deviceLockedMessage.length}"
        RuleField.APP_LIMITS -> "size=${appLimits.size}"
        RuleField.BLOCKED_APPS -> "size=${blockedApps.size} isActive=$isActive"
        RuleField.ACTIVE -> "isActive=$isActive"
    }

    /**
     * Seconds for the wire. 0 means "no limit" and must be sent explicitly: an absent key means
     * "leave unchanged", so omitting it could never clear an existing limit.
     */
    private fun ScreenTimeLimitsUiState.dailyLimitSeconds(): Int =
        if (dailyLimitEnabled) dailyLimitHours * 3600 + dailyLimitMinutes * 60 else 0

    // ---------------------------------------------------------------------------------------
    // Controls
    // ---------------------------------------------------------------------------------------

    /**
     * Toggle daily limit on/off
     */
    fun setDailyLimitEnabled(enabled: Boolean) {
        saveField(
            field = RuleField.DAILY_LIMIT,
            mutate = { it.copy(dailyLimitEnabled = enabled) },
            request = { state ->
                screenTimeRulesRepository.updateScreenTimeRules(
                    deviceId = currentDeviceId,
                    dailyLimit = state.dailyLimitSeconds()
                )
            }
        )
    }

    /**
     * Update daily limit value
     *
     * Called from the time picker's confirm, so this is already an edit-finish signal — there
     * is no intermediate value to save.
     */
    fun setDailyLimit(hours: Int, minutes: Int) {
        saveField(
            field = RuleField.DAILY_LIMIT,
            mutate = {
                it.copy(
                    dailyLimitHours = hours.coerceIn(0, 24),
                    dailyLimitMinutes = minutes.coerceIn(0, 59)
                )
            },
            request = { state ->
                screenTimeRulesRepository.updateScreenTimeRules(
                    deviceId = currentDeviceId,
                    dailyLimit = state.dailyLimitSeconds()
                )
            }
        )
    }

    /**
     * Toggle bedtime mode on/off
     */
    fun setBedtimeEnabled(enabled: Boolean) {
        saveField(
            field = RuleField.BEDTIME,
            mutate = { it.copy(bedtimeEnabled = enabled) },
            request = { state -> saveBedtime(state) }
        )
    }

    /**
     * Update bedtime start time
     */
    fun setBedtimeStart(time: String) {
        saveField(
            field = RuleField.BEDTIME,
            mutate = { it.copy(bedtimeStart = time) },
            request = { state -> saveBedtime(state) }
        )
    }

    /**
     * Update bedtime end time
     */
    fun setBedtimeEnd(time: String) {
        saveField(
            field = RuleField.BEDTIME,
            mutate = { it.copy(bedtimeEnd = time) },
            request = { state -> saveBedtime(state) }
        )
    }

    private suspend fun saveBedtime(state: ScreenTimeLimitsUiState) =
        screenTimeRulesRepository.updateScreenTimeRules(
            deviceId = currentDeviceId,
            bedtimeEnabled = state.bedtimeEnabled,
            bedtimeStart = if (state.bedtimeEnabled) state.bedtimeStart else null,
            bedtimeEnd = if (state.bedtimeEnabled) state.bedtimeEnd else null
        )

    /**
     * Toggle rules active/inactive
     *
     * The master enforcement switch. It had the same never-saved defect as the lock toggles,
     * and the child gates all blocking on it, so leaving it unsaved disabled the whole feature.
     */
    fun setRulesActive(active: Boolean) {
        saveField(
            field = RuleField.ACTIVE,
            mutate = { it.copy(isActive = active) },
            request = { state ->
                screenTimeRulesRepository.updateScreenTimeRules(
                    deviceId = currentDeviceId,
                    isActive = state.isActive
                )
            }
        )
    }

    /**
     * Toggle study time mode on/off
     */
    fun setStudyTimeEnabled(enabled: Boolean) {
        saveField(
            field = RuleField.STUDY_TIME,
            mutate = { it.copy(studyTimeEnabled = enabled) },
            request = { state -> saveStudyTime(state) }
        )
    }

    /**
     * Update study time start time
     */
    fun setStudyTimeStart(time: String) {
        saveField(
            field = RuleField.STUDY_TIME,
            mutate = { it.copy(studyTimeStart = time) },
            request = { state -> saveStudyTime(state) }
        )
    }

    /**
     * Update study time end time
     */
    fun setStudyTimeEnd(time: String) {
        saveField(
            field = RuleField.STUDY_TIME,
            mutate = { it.copy(studyTimeEnd = time) },
            request = { state -> saveStudyTime(state) }
        )
    }

    private suspend fun saveStudyTime(state: ScreenTimeLimitsUiState) =
        screenTimeRulesRepository.updateScreenTimeRules(
            deviceId = currentDeviceId,
            studyTimeEnabled = state.studyTimeEnabled,
            studyTimeStart = if (state.studyTimeEnabled) state.studyTimeStart else null,
            studyTimeEnd = if (state.studyTimeEnabled) state.studyTimeEnd else null
        )

    /**
     * Add an app to study time allowed list
     */
    fun addStudyTimeAllowedApp(packageName: String) {
        if (packageName in _uiState.value.studyTimeAllowedApps) return
        saveField(
            field = RuleField.STUDY_TIME_APPS,
            mutate = { it.copy(studyTimeAllowedApps = it.studyTimeAllowedApps + packageName) },
            request = { state -> saveStudyTimeApps(state) }
        )
    }

    /**
     * Remove an app from study time allowed list
     */
    fun removeStudyTimeAllowedApp(packageName: String) {
        saveField(
            field = RuleField.STUDY_TIME_APPS,
            mutate = { it.copy(studyTimeAllowedApps = it.studyTimeAllowedApps - packageName) },
            request = { state -> saveStudyTimeApps(state) }
        )
    }

    /**
     * An emptied list is sent as `[]`, not omitted. Gson drops nulls, and the backend reads an
     * absent key as "leave unchanged", so coercing empty to null would make the last removal
     * impossible to express.
     */
    private suspend fun saveStudyTimeApps(state: ScreenTimeLimitsUiState) =
        screenTimeRulesRepository.updateScreenTimeRules(
            deviceId = currentDeviceId,
            studyTimeAllowedApps = state.studyTimeAllowedApps
        )

    /**
     * Toggle device lock on/off
     */
    fun setDeviceLocked(locked: Boolean) {
        saveField(
            field = RuleField.DEVICE_LOCK,
            mutate = { it.copy(isDeviceLocked = locked) },
            request = { state -> saveDeviceLock(state) }
        )
    }

    /**
     * Update device locked message on screen only.
     *
     * A text field has no settled value while it is being typed into, so this does not save.
     * [commitDeviceLockedMessage] is the edit-finish signal.
     */
    fun setDeviceLockedMessage(message: String) {
        _uiState.update { it.copy(deviceLockedMessage = message) }
    }

    /**
     * Persist the locked message once editing has finished (focus lost, or keyboard done).
     */
    fun commitDeviceLockedMessage() {
        val state = _uiState.value
        if (state.deviceLockedMessage == state.confirmedState.deviceLockedMessage) return
        saveField(
            field = RuleField.DEVICE_LOCK,
            mutate = { it },
            request = { current -> saveDeviceLock(current) }
        )
    }

    private suspend fun saveDeviceLock(state: ScreenTimeLimitsUiState) =
        screenTimeRulesRepository.updateScreenTimeRules(
            deviceId = currentDeviceId,
            isDeviceLocked = state.isDeviceLocked,
            deviceLockedMessage = state.deviceLockedMessage
        )

    /**
     * Add or update an app limit
     * Uses immutable map operations for predictable Compose state updates
     */
    fun addAppLimit(packageName: String, limitSeconds: Int) {
        saveField(
            field = RuleField.APP_LIMITS,
            mutate = { it.copy(appLimits = it.appLimits + (packageName to limitSeconds)) },
            request = { state -> saveAppLimits(state) }
        )
    }

    /**
     * Remove an app limit
     */
    fun removeAppLimit(packageName: String) {
        saveField(
            field = RuleField.APP_LIMITS,
            mutate = { it.copy(appLimits = it.appLimits - packageName) },
            request = { state -> saveAppLimits(state) }
        )
    }

    /** An emptied map is sent as `{}`, for the same reason an emptied list is sent as `[]`. */
    private suspend fun saveAppLimits(state: ScreenTimeLimitsUiState) =
        screenTimeRulesRepository.updateScreenTimeRules(
            deviceId = currentDeviceId,
            appLimits = state.appLimits
        )

    /**
     * Add an app to blocked list
     *
     * Also asserts that the rule set is active, but only when the parent's master enforcement
     * switch is already on: a lock must never silently re-enable a policy the parent switched
     * off, and the child refuses to block anything while the rule set is inactive.
     */
    fun addBlockedApp(packageName: String) {
        if (packageName in _uiState.value.blockedApps) return
        saveField(
            field = RuleField.BLOCKED_APPS,
            mutate = { it.copy(blockedApps = it.blockedApps + packageName) },
            request = { state ->
                screenTimeRulesRepository.updateScreenTimeRules(
                    deviceId = currentDeviceId,
                    blockedApps = state.blockedApps,
                    isActive = if (state.isActive) true else null
                )
            }
        )
    }

    /**
     * Remove an app from blocked list
     *
     * Removing the last entry sends `"blocked_apps": []`. Sending nothing, which is what the
     * old `ifEmpty { null }` did, left the child enforcing a lock the parent had removed.
     */
    fun removeBlockedApp(packageName: String) {
        saveField(
            field = RuleField.BLOCKED_APPS,
            mutate = { it.copy(blockedApps = it.blockedApps - packageName) },
            request = { state ->
                screenTimeRulesRepository.updateScreenTimeRules(
                    deviceId = currentDeviceId,
                    blockedApps = state.blockedApps
                )
            }
        )
    }

    /**
     * Delete all rules for this device
     */
    fun deleteRules() {
        if (currentDeviceId < 0) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = screenTimeRulesRepository.deleteScreenTimeRules(currentDeviceId)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        ScreenTimeLimitsUiState(
                            successMessage = getString(R.string.screentime_msg_deleted)
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.message)
                    }
                }
                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    /**
     * Clear error and success messages
     */
    fun clearMessages() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }
}
