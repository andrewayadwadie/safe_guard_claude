package com.safeguard.parentalcontrol.presentation.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.BuildConfig
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.data.repository.CategoryAlertStats
import com.safeguard.parentalcontrol.util.LocaleHelper
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Content category with display information.
 */
data class MonitoringCategory(
    val id: String,
    val displayName: String,
    val description: String,
    val isEnabled: Boolean,
    val isCritical: Boolean = false, // Critical categories cannot be disabled
    val alertStats: CategoryAlertStats? = null
)

/**
 * UI state for text monitoring settings.
 */
data class TextMonitoringSettingsUiState(
    val isLoading: Boolean = true,
    val categories: List<MonitoringCategory> = emptyList(),
    val isTextMonitoringEnabled: Boolean = true,
    val alertSoundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null
)

/**
 * ViewModel for text monitoring settings.
 *
 * Allows parents to configure:
 * - Which content categories to monitor
 * - Alert preferences (sound, vibration)
 * - View alert statistics per category
 */
@HiltViewModel
class TextMonitoringSettingsViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager,
    private val alertRepository: AlertRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(context).getString(resId, *args)

    companion object {
        private const val TAG = "TextMonitoringSettingsVM"

        // Preference keys
        private const val PREF_TEXT_MONITORING_ENABLED = "text_monitoring_enabled"
        private const val PREF_ALERT_SOUND_ENABLED = "alert_sound_enabled"
        private const val PREF_VIBRATION_ENABLED = "alert_vibration_enabled"
        private const val PREF_CATEGORY_PREFIX = "category_enabled_"
    }

    private val _uiState = MutableStateFlow(TextMonitoringSettingsUiState())
    val uiState: StateFlow<TextMonitoringSettingsUiState> = _uiState.asStateFlow()

    // All monitoring categories with their default enabled state
    private val allCategories: List<MonitoringCategory>
        get() = listOf(
            MonitoringCategory(
                id = "self_harm",
                displayName = getString(R.string.textmon_cat_selfharm_name),
                description = getString(R.string.textmon_cat_selfharm_desc),
                isEnabled = true,
                isCritical = true
            ),
            MonitoringCategory(
                id = "predator_grooming",
                displayName = getString(R.string.textmon_cat_predator_name),
                description = getString(R.string.textmon_cat_predator_desc),
                isEnabled = true,
                isCritical = true
            ),
            MonitoringCategory(
                id = "sexual",
                displayName = getString(R.string.textmon_cat_sexual_name),
                description = getString(R.string.textmon_cat_sexual_desc),
                isEnabled = true,
                isCritical = false
            ),
            MonitoringCategory(
                id = "violence",
                displayName = getString(R.string.textmon_cat_violence_name),
                description = getString(R.string.textmon_cat_violence_desc),
                isEnabled = true,
                isCritical = false
            ),
            MonitoringCategory(
                id = "bullying",
                displayName = getString(R.string.textmon_cat_bullying_name),
                description = getString(R.string.textmon_cat_bullying_desc),
                isEnabled = true,
                isCritical = false
            ),
            MonitoringCategory(
                id = "drugs",
                displayName = getString(R.string.textmon_cat_drugs_name),
                description = getString(R.string.textmon_cat_drugs_desc),
                isEnabled = true,
                isCritical = false
            ),
            MonitoringCategory(
                id = "profanity",
                displayName = getString(R.string.textmon_cat_profanity_name),
                description = getString(R.string.textmon_cat_profanity_desc),
                isEnabled = true,
                isCritical = false
            ),
            MonitoringCategory(
                id = "custom",
                displayName = getString(R.string.textmon_cat_custom_name),
                description = getString(R.string.textmon_cat_custom_desc),
                isEnabled = true,
                isCritical = false
            )
        )

    init {
        loadSettings()
    }

    /**
     * Load settings from preferences.
     */
    private fun loadSettings() {
        viewModelScope.launch {
            try {
                val isEnabled = preferencesManager.getBoolean(PREF_TEXT_MONITORING_ENABLED, true)
                val soundEnabled = preferencesManager.getBoolean(PREF_ALERT_SOUND_ENABLED, true)
                val vibrationEnabled = preferencesManager.getBoolean(PREF_VIBRATION_ENABLED, true)

                // Load category states and alert stats
                val categoriesWithState = allCategories.map { category ->
                    val categoryEnabled = if (category.isCritical) {
                        true // Critical categories are always enabled
                    } else {
                        preferencesManager.getBoolean(
                            PREF_CATEGORY_PREFIX + category.id,
                            category.isEnabled
                        )
                    }

                    // Get alert stats for this category
                    val stats = try {
                        alertRepository.getCategoryAlertStats(category.id)
                    } catch (e: Exception) {
                        null
                    }

                    category.copy(
                        isEnabled = categoryEnabled,
                        alertStats = stats
                    )
                }

                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        isTextMonitoringEnabled = isEnabled,
                        alertSoundEnabled = soundEnabled,
                        vibrationEnabled = vibrationEnabled,
                        categories = categoriesWithState
                    )
                }

                Timber.d("$TAG: Settings loaded - monitoring=$isEnabled, categories=${categoriesWithState.count { it.isEnabled }}")

            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error loading settings")
                _uiState.update { it.copy(isLoading = false, error = getString(R.string.textmon_error_load)) }
            }
        }
    }

    /**
     * Toggle text monitoring on/off.
     */
    fun setTextMonitoringEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.putBoolean(PREF_TEXT_MONITORING_ENABLED, enabled)
            _uiState.update { it.copy(isTextMonitoringEnabled = enabled) }
            Timber.d("$TAG: Text monitoring ${if (enabled) "enabled" else "disabled"}")
        }
    }

    /**
     * Toggle alert sound on/off.
     */
    fun setAlertSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.putBoolean(PREF_ALERT_SOUND_ENABLED, enabled)
            _uiState.update { it.copy(alertSoundEnabled = enabled) }
        }
    }

    /**
     * Toggle vibration on/off.
     */
    fun setVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.putBoolean(PREF_VIBRATION_ENABLED, enabled)
            _uiState.update { it.copy(vibrationEnabled = enabled) }
        }
    }

    /**
     * Toggle a specific category on/off.
     * Critical categories cannot be disabled.
     */
    fun setCategoryEnabled(categoryId: String, enabled: Boolean) {
        viewModelScope.launch {
            val category = allCategories.find { it.id == categoryId } ?: return@launch

            // Don't allow disabling critical categories
            if (category.isCritical && !enabled) {
                Timber.w("$TAG: Cannot disable critical category: $categoryId")
                _uiState.update { it.copy(error = getString(R.string.textmon_error_critical_disable)) }
                return@launch
            }

            preferencesManager.putBoolean(PREF_CATEGORY_PREFIX + categoryId, enabled)

            _uiState.update { state ->
                state.copy(
                    categories = state.categories.map {
                        if (it.id == categoryId) it.copy(isEnabled = enabled) else it
                    }
                )
            }

            Timber.d("$TAG: Category $categoryId ${if (enabled) "enabled" else "disabled"}")
        }
    }

    /**
     * Check if a specific category is enabled.
     */
    fun isCategoryEnabled(categoryId: String): Boolean {
        return _uiState.value.categories.find { it.id == categoryId }?.isEnabled ?: true
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Fire a synthetic alert down the real delivery path. **Debug builds only** — the trigger
     * is not rendered in a release build and the repository refuses the call there too.
     *
     * Reports the outcome through the existing error channel so a tester sees the HTTP result
     * on screen instead of having to hold a logcat filter open, though the ALERT_PIPE trace is
     * still the fuller picture.
     */
    fun fireTestAlert() {
        if (!BuildConfig.DEBUG) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val result = alertRepository.createDebugTestAlert()
            val message = when (result) {
                is NetworkResult.Success -> "Test alert sent — alert id ${result.data.id}"
                is NetworkResult.Error -> "Test alert failed (${result.code ?: "no response"}): ${result.message}"
                is NetworkResult.Loading -> "Test alert in flight"
            }
            Timber.i("$TAG: $message")
            _uiState.update { it.copy(isSaving = false, error = message) }
        }
    }

    /**
     * Refresh alert statistics.
     */
    fun refreshStats() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            loadSettings()
        }
    }
}
