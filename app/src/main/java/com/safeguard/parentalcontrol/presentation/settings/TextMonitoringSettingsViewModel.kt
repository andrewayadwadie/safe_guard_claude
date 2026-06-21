package com.safeguard.parentalcontrol.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.data.repository.CategoryAlertStats
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val alertRepository: AlertRepository
) : ViewModel() {

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
    private val allCategories = listOf(
        MonitoringCategory(
            id = "self_harm",
            displayName = "Self-Harm & Suicide",
            description = "Detects content related to self-harm, suicide, or harmful ideation. " +
                    "This is always enabled for child safety.",
            isEnabled = true,
            isCritical = true
        ),
        MonitoringCategory(
            id = "predator_grooming",
            displayName = "Predatory Behavior",
            description = "Detects potential grooming patterns and suspicious contact attempts. " +
                    "This is always enabled for child safety.",
            isEnabled = true,
            isCritical = true
        ),
        MonitoringCategory(
            id = "sexual",
            displayName = "Sexual Content",
            description = "Detects sexually explicit or inappropriate content including sexting.",
            isEnabled = true,
            isCritical = false
        ),
        MonitoringCategory(
            id = "violence",
            displayName = "Violence & Threats",
            description = "Detects violent content, threats, and aggressive language.",
            isEnabled = true,
            isCritical = false
        ),
        MonitoringCategory(
            id = "bullying",
            displayName = "Cyberbullying",
            description = "Detects bullying, harassment, and mean-spirited content.",
            isEnabled = true,
            isCritical = false
        ),
        MonitoringCategory(
            id = "drugs",
            displayName = "Drugs & Substances",
            description = "Detects references to drugs, alcohol, and substance abuse.",
            isEnabled = true,
            isCritical = false
        ),
        MonitoringCategory(
            id = "profanity",
            displayName = "Profanity & Bad Language",
            description = "Detects profanity, swear words, and inappropriate language.",
            isEnabled = true,
            isCritical = false
        ),
        MonitoringCategory(
            id = "custom",
            displayName = "Custom Blacklist",
            description = "Detects words from your custom blacklist.",
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
                _uiState.update { it.copy(isLoading = false, error = "Failed to load settings") }
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
                _uiState.update { it.copy(error = "This category cannot be disabled for safety reasons") }
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
     * Refresh alert statistics.
     */
    fun refreshStats() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            loadSettings()
        }
    }
}
