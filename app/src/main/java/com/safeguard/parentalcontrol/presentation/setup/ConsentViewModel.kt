package com.safeguard.parentalcontrol.presentation.setup

import androidx.lifecycle.ViewModel
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import timber.log.Timber
import javax.inject.Inject

/**
 * Backs the monitoring-consent screen. Persists the parent's acknowledgement of the
 * in-app monitoring disclosure (Google Play Prominent Disclosure & Consent), which is
 * the gate every monitoring service checks before it may run.
 */
@HiltViewModel
class ConsentViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    /** Record that the parent has read the disclosure and consented to monitoring. */
    fun grantConsent() {
        preferencesManager.monitoringConsentGranted = true
        Timber.d("Monitoring consent granted by parent")
    }
}
