package com.safeguard.parentalcontrol.data.account

import com.safeguard.parentalcontrol.data.model.DeleteAccountRequest
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AuthRepository
import com.safeguard.parentalcontrol.data.repository.DeletionError
import com.safeguard.parentalcontrol.data.repository.FamilyRepository
import com.safeguard.parentalcontrol.util.AccountDeletionState
import com.safeguard.parentalcontrol.util.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of one deletion target (the signed-in account, or one child's account). */
sealed interface DeletionState {
    data object Idle : DeletionState
    data object InFlight : DeletionState
    data object Succeeded : DeletionState
    data class Failed(val error: DeletionError) : DeletionState
}

/**
 * How a parent proves who they are before a child's account is deleted. Both variants redact
 * their secret in `toString()`.
 */
sealed interface ChildReauth {
    data class Password(val value: String) : ChildReauth {
        override fun toString() = "Password(<redacted>)"
    }

    data class Google(val idToken: String) : ChildReauth {
        override fun toString() = "Google(<redacted>)"
    }
}

/**
 * Application-scoped owner of every account deletion. It exists because the request and, once the
 * server has confirmed, the teardown must outlive the ViewModel: `viewModelScope` is cancelled the
 * moment the user leaves the screen, which would leave a deleted account with live local data.
 * It also makes each target single-flight across rotation and navigation.
 */
@Singleton
class AccountDeletionCoordinator internal constructor(
    private val authRepository: AuthRepository,
    private val familyRepository: FamilyRepository,
    private val teardownManager: AccountTeardownManager,
    private val accountDeletionState: AccountDeletionState,
    private val preferencesManager: PreferencesManager,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(
        authRepository: AuthRepository,
        familyRepository: FamilyRepository,
        teardownManager: AccountTeardownManager,
        accountDeletionState: AccountDeletionState,
        preferencesManager: PreferencesManager
    ) : this(
        authRepository, familyRepository, teardownManager, accountDeletionState, preferencesManager,
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private val _selfState = MutableStateFlow<DeletionState>(DeletionState.Idle)
    val selfState: StateFlow<DeletionState> = _selfState.asStateFlow()

    /**
     * Deletes the signed-in account. Returns immediately, without a second request, while one is
     * already in flight. The outcome is delivered through [selfState].
     */
    fun deleteOwnAccount(request: DeleteAccountRequest) {
        if (!tryBeginSelf()) {
            Timber.tag(TAG).i("self delete ignored: already in flight")
            return
        }
        scope.launch { runSelfDelete(request) }
    }

    /** Resets a finished outcome once the UI has consumed it. */
    fun acknowledgeSelf() {
        _selfState.update { if (it is DeletionState.Failed || it is DeletionState.Succeeded) DeletionState.Idle else it }
    }

    /**
     * Called by every new delete-account ViewModel. A leftover terminal state from an earlier
     * deletion in this process must never navigate someone who has since signed in again.
     */
    fun attachSelf() {
        _selfState.update { if (it is DeletionState.InFlight) it else DeletionState.Idle }
    }

    private val _childStates = MutableStateFlow<Map<Int, DeletionState>>(emptyMap())

    /** Progress per child id (`FamilyLink.childId`). A missing entry means idle. */
    val childStates: StateFlow<Map<Int, DeletionState>> = _childStates.asStateFlow()

    /**
     * Deletes one linked child's ACCOUNT after the parent has re-authenticated with the server.
     * Single flight per [childId]. There is no marker and no local teardown here: nothing on this
     * device belongs to the child, and the parent stays signed in.
     */
    fun deleteChildAccount(childId: Int, reauth: ChildReauth) {
        if (!tryBeginChild(childId)) {
            Timber.tag(TAG).i("child delete ignored: already in flight")
            return
        }
        scope.launch { runChildDelete(childId, reauth) }
    }

    fun acknowledgeChild(childId: Int) {
        _childStates.update { it - childId }
    }

    private fun tryBeginChild(childId: Int): Boolean {
        var started = false
        _childStates.update { states ->
            started = states[childId] !is DeletionState.InFlight
            if (started) states + (childId to DeletionState.InFlight) else states
        }
        return started
    }

    private fun setChild(childId: Int, state: DeletionState) {
        _childStates.update { it + (childId to state) }
    }

    private suspend fun runChildDelete(childId: Int, reauth: ChildReauth) {
        val reauthResult = when (reauth) {
            is ChildReauth.Password -> authRepository.reauthenticateWithPassword(reauth.value)
            is ChildReauth.Google -> authRepository.reauthenticateWithGoogle(reauth.idToken)
        }
        if (reauthResult is NetworkResult.Error) {
            val mapped = DeletionError.fromReauth(reauthResult)
            Timber.tag(TAG).i("child reauth failed code=%s mapped=%s", reauthResult.code ?: "none", mapped.javaClass.simpleName)
            setChild(childId, DeletionState.Failed(mapped))
            return
        }

        when (val result = familyRepository.deleteChildAccount(childId)) {
            is NetworkResult.Success -> setChild(childId, DeletionState.Succeeded)
            is NetworkResult.Error -> {
                val mapped = DeletionError.fromChildDelete(result)
                Timber.tag(TAG).i("child delete failed code=%s mapped=%s", result.code ?: "none", mapped.javaClass.simpleName)
                setChild(childId, DeletionState.Failed(mapped))
            }
            NetworkResult.Loading -> acknowledgeChild(childId)
        }
    }

    private fun tryBeginSelf(): Boolean {
        while (true) {
            val current = _selfState.value
            if (current is DeletionState.InFlight) return false
            if (_selfState.compareAndSet(current, DeletionState.InFlight)) return true
        }
    }

    private suspend fun runSelfDelete(request: DeleteAccountRequest) {
        // Written before the request is sent: if the process dies mid-request, the next launch
        // can tell "the server may have deleted it" from "nothing was ever sent" (FR-016a).
        accountDeletionState.markPending(preferencesManager.isParent, preferencesManager.hasLinkedParent)

        when (val result = authRepository.deleteAccount(request)) {
            is NetworkResult.Success -> {
                Timber.tag(TAG).i("server confirmed deletion, starting teardown")
                teardownManager.teardown(TeardownReason.ACCOUNT_DELETED)
                _selfState.value = DeletionState.Succeeded
            }
            is NetworkResult.Error -> handleSelfError(result)
            NetworkResult.Loading -> _selfState.value = DeletionState.Idle
        }
    }

    private suspend fun handleSelfError(error: NetworkResult.Error) {
        val mapped = DeletionError.fromSelfDelete(error)
        Timber.tag(TAG).i("failed code=%s mapped=%s", error.code ?: "none", mapped.javaClass.simpleName)
        when (mapped) {
            // The response may have been lost after the server acted, so the marker stays: the next
            // attempt or launch settles it. Nothing is stopped and nothing is claimed.
            DeletionError.Network -> Unit

            // A lost success looks exactly like an expired session (both are 401), so settle it now.
            DeletionError.SessionOver -> onSessionGoneWithMarker()

            // A definite answer from the server: nothing was deleted.
            else -> accountDeletionState.clearPending()
        }
        _selfState.value = DeletionState.Failed(mapped)
    }

    /** True while a deletion marker or an unfinished teardown is waiting to be settled. */
    fun hasPendingRecovery(): Boolean =
        accountDeletionState.teardownInProgress() != null || accountDeletionState.pending() != null

    /**
     * Settles a deletion that this device never saw the end of (FR-016a). Suspends so the caller
     * can wait for it before starting anything else.
     *
     * - A teardown that was interrupted is finished with the same reason.
     * - A marker plus a live account (`auth/me` 200) is cleared: the deletion never happened.
     * - A marker plus 401 goes through [onSessionGoneWithMarker].
     * - A marker plus a network error is kept for the next attempt. Nothing is stopped.
     */
    suspend fun resumePendingIfAny() {
        // A live request in this process owns the marker; do not race it.
        if (_selfState.value is DeletionState.InFlight) return

        val interrupted = accountDeletionState.teardownInProgress()
        if (interrupted != null) {
            Timber.tag(TAG).i("recovery: resuming interrupted teardown reason=%s", interrupted.name)
            teardownManager.teardown(interrupted)
            return
        }

        if (accountDeletionState.pending() == null) return

        // Nothing to protect or wipe without a session, for example a marker that came along in a
        // device-to-device transfer, or a user who signed out after a network error.
        if (!preferencesManager.isLoggedIn) {
            Timber.tag(TAG).i("recovery: marker without a signed-in session, discarded")
            accountDeletionState.clearPending()
            return
        }

        when (val result = authRepository.getCurrentUser()) {
            is NetworkResult.Success -> {
                Timber.tag(TAG).i("recovery: account still exists, marker cleared")
                accountDeletionState.clearPending()
            }
            is NetworkResult.Error -> {
                if (result.code == 401) {
                    onSessionGoneWithMarker()
                } else {
                    Timber.tag(TAG).i("recovery: could not reach the server (code=%s), marker kept", result.code ?: "none")
                }
            }
            NetworkResult.Loading -> Unit
        }
    }

    /**
     * The session is gone while a deletion marker is present. Wipe local data (with neutral
     * "session ended" wording, never "account deleted") only where that cannot remove protection
     * from a supervised child: a parent device, or a child the server last reported as unlinked.
     * Nothing that can be forged on the device is trusted to stop a linked child's protection.
     */
    internal suspend fun onSessionGoneWithMarker() {
        val marker = accountDeletionState.pending() ?: return
        if (marker.roleWasParent || !marker.hadLinkedParent) {
            Timber.tag(TAG).i("recovery: session gone with marker, running teardown")
            teardownManager.teardown(TeardownReason.SESSION_GONE_AFTER_MARKER)
        } else {
            Timber.tag(TAG).i("recovery: linked child, protection left untouched")
            accountDeletionState.clearPending()
        }
    }

    private companion object {
        const val TAG = "AccountDeletion"
    }
}
