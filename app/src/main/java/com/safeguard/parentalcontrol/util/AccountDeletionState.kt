package com.safeguard.parentalcontrol.util

import android.content.Context
import android.content.SharedPreferences
import com.safeguard.parentalcontrol.data.account.TeardownReason
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A deletion request that was sent but whose outcome this device never saw. */
data class PendingMarker(
    val sinceMs: Long,
    val roleWasParent: Boolean,
    val hadLinkedParent: Boolean
)

/**
 * Durable deletion state. It is deliberately a plain SharedPreferences file that
 * [PreferencesManager.clearAll] does not touch, so it outlives the wipe it describes. It holds no
 * PII: only timestamps, role flags and notice flags. Every write uses `commit()` so it survives the
 * process being killed straight afterwards.
 *
 * - pending: written just before `DELETE /auth/account` is sent; cleared on a definite answer.
 * - teardown in progress: written when the ordered teardown starts; cleared when it completes.
 * - notice flags: read by the Login screen to show "Account deleted" after a cold start.
 */
@Singleton
class AccountDeletionState @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    fun markPending(roleWasParent: Boolean, hadLinkedParent: Boolean) {
        prefs.edit()
            .putLong(KEY_PENDING_SINCE, System.currentTimeMillis())
            .putBoolean(KEY_PENDING_ROLE_PARENT, roleWasParent)
            .putBoolean(KEY_PENDING_HAD_LINKED_PARENT, hadLinkedParent)
            .commit()
    }

    fun pending(): PendingMarker? {
        val since = prefs.getLong(KEY_PENDING_SINCE, 0L)
        if (since == 0L) return null
        return PendingMarker(
            sinceMs = since,
            roleWasParent = prefs.getBoolean(KEY_PENDING_ROLE_PARENT, false),
            hadLinkedParent = prefs.getBoolean(KEY_PENDING_HAD_LINKED_PARENT, false)
        )
    }

    fun clearPending() {
        prefs.edit()
            .remove(KEY_PENDING_SINCE)
            .remove(KEY_PENDING_ROLE_PARENT)
            .remove(KEY_PENDING_HAD_LINKED_PARENT)
            .commit()
    }

    fun markTeardownInProgress(reason: TeardownReason) {
        prefs.edit().putString(KEY_TEARDOWN_REASON, reason.name).commit()
    }

    fun teardownInProgress(): TeardownReason? {
        val name = prefs.getString(KEY_TEARDOWN_REASON, null) ?: return null
        return TeardownReason.values().firstOrNull { it.name == name }
    }

    /**
     * Raises the post-deletion notice WITHOUT ending the teardown. It must run before the
     * AccountDeleted event is emitted: that event navigates to Login straight away, and the Login
     * screen reads these flags when it is created.
     */
    fun raiseNotice(showNotice: Boolean, childDevice: Boolean) {
        prefs.edit()
            .putBoolean(KEY_NOTICE_PENDING, showNotice)
            .putBoolean(KEY_NOTICE_CHILD_UNINSTALL, showNotice && childDevice)
            .commit()
    }

    /**
     * Ends a teardown: clears the pending and in-progress markers and, only after a server-confirmed
     * deletion, raises the notice the Login screen shows. [childDevice] also raises the uninstall
     * note (protection is off and the user must remove the app or its permissions themselves).
     */
    fun completeTeardown(showNotice: Boolean, childDevice: Boolean) {
        prefs.edit()
            .remove(KEY_PENDING_SINCE)
            .remove(KEY_PENDING_ROLE_PARENT)
            .remove(KEY_PENDING_HAD_LINKED_PARENT)
            .remove(KEY_TEARDOWN_REASON)
            .putBoolean(KEY_NOTICE_PENDING, showNotice)
            .putBoolean(KEY_NOTICE_CHILD_UNINSTALL, showNotice && childDevice)
            .commit()
    }

    fun noticePending(): Boolean = prefs.getBoolean(KEY_NOTICE_PENDING, false)

    fun childUninstallNotice(): Boolean = prefs.getBoolean(KEY_NOTICE_CHILD_UNINSTALL, false)

    fun dismissNotice() {
        prefs.edit()
            .remove(KEY_NOTICE_PENDING)
            .remove(KEY_NOTICE_CHILD_UNINSTALL)
            .commit()
    }

    private companion object {
        const val FILE_NAME = "account_deletion_state"
        const val KEY_PENDING_SINCE = "pending_since"
        const val KEY_PENDING_ROLE_PARENT = "pending_role_parent"
        const val KEY_PENDING_HAD_LINKED_PARENT = "pending_had_linked_parent"
        const val KEY_TEARDOWN_REASON = "teardown_reason"
        const val KEY_NOTICE_PENDING = "notice_pending"
        const val KEY_NOTICE_CHILD_UNINSTALL = "notice_child_uninstall"
    }
}
