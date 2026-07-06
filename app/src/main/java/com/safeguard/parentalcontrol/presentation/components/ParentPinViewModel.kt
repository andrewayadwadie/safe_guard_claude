package com.safeguard.parentalcontrol.presentation.components

import androidx.lifecycle.ViewModel
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Backs [ParentPinGate]. Exposes the on-device parent PIN operations (create / verify)
 * so the gate can run without each screen wiring its own PIN access.
 */
@HiltViewModel
class ParentPinViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager
) : ViewModel() {
    val hasParentPin: Boolean
        get() = preferencesManager.hasParentPin

    fun verifyParentPin(pin: String): Boolean = preferencesManager.verifyParentPin(pin)

    fun setParentPin(pin: String) = preferencesManager.setParentPin(pin)
}
