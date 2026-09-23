package com.safeguard.parentalcontrol.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a notification tap wants the app to go, held until the UI can act on it.
 *
 * Process-scoped rather than back-stack-scoped for two reasons:
 * - The target must survive a login detour. If the session has expired when the parent taps,
 *   navigation goes to Login first and the login flow destroys the back stack, so anything
 *   stored in a `SavedStateHandle` would be lost.
 * - The value is consumed exactly once. Screen recreation (rotation) then finds nothing
 *   pending and does not navigate a second time — which reading the Activity intent directly
 *   would do, because the intent stays attached.
 */
@Singleton
class DeepLinkHolder @Inject constructor() {

    private val _pending = MutableStateFlow<PendingDeepLink?>(null)
    val pending: StateFlow<PendingDeepLink?> = _pending.asStateFlow()

    fun post(link: PendingDeepLink) {
        _pending.value = link
    }

    /** Clear the pending target. Called in the same effect that performs the navigation. */
    fun consume() {
        _pending.value = null
    }
}

/**
 * A pending navigation to the existing Alerts route.
 *
 * A null [deviceId] means the unfiltered Alerts screen — used when the push carried no device
 * and as the fallback when the target device has been unlinked.
 *
 * [alertId] is the specific alert the notification was about. Null when the push carried none
 * or carried an unparseable one, in which case the screen simply opens unhighlighted.
 */
data class PendingDeepLink(
    val deviceId: Int?,
    val deviceName: String?,
    val alertId: Int? = null
)
