package com.safeguard.parentalcontrol.data.account

import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.data.repository.CustomWordRepository
import com.safeguard.parentalcontrol.data.repository.ScreenTimeRulesRepository
import com.safeguard.parentalcontrol.util.AccountDeletionState
import com.safeguard.parentalcontrol.util.ImageHasher
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.TextHasher
import com.safeguard.parentalcontrol.util.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class TeardownReason {
    /** The server returned 2xx: emit AccountDeleted and raise the post-deletion notice. */
    ACCOUNT_DELETED,

    /** A leftover deletion marker met a 401: wipe everything but never claim "deleted". */
    SESSION_GONE_AFTER_MARKER
}

/**
 * Ordered, idempotent, non-cancellable local teardown after an account deletion. Used only by the
 * deletion flow; the logout flow is intentionally not routed through it.
 *
 * The order is the design (see contracts/teardown.md): background work and services stop, and the
 * content-filtering preference goes off, BEFORE preferences and tokens are cleared. Nothing can then
 * hit a 401 and raise a "session ended" toast, and the VPN's own shutdown sees the preference off,
 * so it raises no tamper alert. Every step runs in its own try/catch: a partial wipe beats an
 * aborted one.
 */
@Singleton
class AccountTeardownManager @Inject constructor(
    private val ops: DeviceTeardownOps,
    private val alertRepository: AlertRepository,
    private val customWordRepository: CustomWordRepository,
    private val screenTimeRulesRepository: ScreenTimeRulesRepository,
    private val imageHasher: ImageHasher,
    private val textHasher: TextHasher,
    private val preferencesManager: PreferencesManager,
    private val tokenManager: TokenManager,
    private val accountDeletionState: AccountDeletionState
) {
    suspend fun teardown(reason: TeardownReason) = withContext(NonCancellable + Dispatchers.IO) {
        val startedAt = System.nanoTime()

        // Captured before the wipe: preferences are gone by the time the notice is raised.
        val wasChild = try {
            preferencesManager.isChild
        } catch (e: Exception) {
            false
        }
        Timber.tag(TAG).i("teardown role=%s reason=%s", if (wasChild) "child" else "parent", reason.name)

        step("marker") { accountDeletionState.markTeardownInProgress(reason) }

        // Off BEFORE the VPN is stopped: its onDestroy only raises "vpn_disconnected" while this is on.
        step("filteringOff") { preferencesManager.isContentFilteringEnabled = false }

        step("cancelWork") { ops.cancelAllWork() }
        step("cancelAlarm") { ops.cancelMonitoringRestartAlarm() }

        step("stopMonitoring") { ops.stopMonitoringService() }
        step("stopVpn") { ops.stopVpn() }

        step("evictHttpCache") { ops.evictHttpCache() }
        step("clearPendingAlerts") { alertRepository.clearPendingAlerts() }
        step("clearCooldowns") { alertRepository.clearCooldowns() }
        step("clearWordCache") { customWordRepository.clearCache() }
        step("clearRulesCache") { screenTimeRulesRepository.clearCache() }
        step("clearImageHasher") { imageHasher.clearCache() }
        step("clearTextHasher") { textHasher.clearCache() }
        step("deleteImageBackups") { ops.deleteImageBackups() }
        step("cancelNotifications") { ops.cancelAllNotifications() }
        step("deleteFcmToken") { ops.deleteFcmToken() }
        step("resetAnalytics") { ops.resetAnalytics() }

        step("clearPrefs") { preferencesManager.clearAll() }
        // Before the token clear: that emits AccountDeleted, which navigates to Login at once, and
        // the Login screen reads the notice flags when it is created.
        step("notice") {
            accountDeletionState.raiseNotice(
                showNotice = reason == TeardownReason.ACCOUNT_DELETED,
                childDevice = wasChild
            )
        }
        // Tokens last of the wipe: nothing that ran earlier can reach the network with a dead token.
        step("clearTokens") {
            tokenManager.clearTokensDueToAccountDeletion(emit = reason == TeardownReason.ACCOUNT_DELETED)
        }

        // Safety net for a tamper alert that raced into the queue while services were stopping.
        step("finalCancelWork") { ops.cancelAllWork() }
        step("complete") {
            accountDeletionState.completeTeardown(
                showNotice = reason == TeardownReason.ACCOUNT_DELETED,
                childDevice = wasChild
            )
        }

        Timber.tag(TAG).i("teardown done in %dms", (System.nanoTime() - startedAt) / 1_000_000)
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
            Timber.tag(TAG).i("teardown step=%s ok", name)
        } catch (e: Exception) {
            Timber.tag(TAG).w("teardown step=%s failed: %s", name, e.javaClass.simpleName)
        }
    }

    private companion object {
        const val TAG = "AccountDeletion"
    }
}
