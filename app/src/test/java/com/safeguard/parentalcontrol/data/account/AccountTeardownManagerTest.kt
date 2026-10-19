package com.safeguard.parentalcontrol.data.account

import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.data.repository.CustomWordRepository
import com.safeguard.parentalcontrol.data.repository.ScreenTimeRulesRepository
import com.safeguard.parentalcontrol.util.AccountDeletionState
import com.safeguard.parentalcontrol.util.ImageHasher
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.TextHasher
import com.safeguard.parentalcontrol.util.TokenManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

class AccountTeardownManagerTest {

    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private lateinit var ops: DeviceTeardownOps
    private lateinit var alertRepository: AlertRepository
    private lateinit var customWordRepository: CustomWordRepository
    private lateinit var screenTimeRulesRepository: ScreenTimeRulesRepository
    private lateinit var imageHasher: ImageHasher
    private lateinit var textHasher: TextHasher
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var tokenManager: TokenManager
    private lateinit var state: AccountDeletionState
    private lateinit var manager: AccountTeardownManager

    private var prefsCleared = false

    @Before
    fun setup() {
        log.clear()
        prefsCleared = false
        ops = mockk(relaxed = true)
        alertRepository = mockk(relaxed = true)
        customWordRepository = mockk(relaxed = true)
        screenTimeRulesRepository = mockk(relaxed = true)
        imageHasher = mockk(relaxed = true)
        textHasher = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        state = mockk(relaxed = true)

        every { ops.cancelAllWork() } answers { log += "cancelAllWork" }
        every { ops.cancelMonitoringRestartAlarm() } answers { log += "cancelAlarm" }
        every { ops.stopMonitoringService() } answers { log += "stopMonitoring" }
        every { ops.stopVpn() } answers { log += "stopVpn" }
        every { ops.evictHttpCache() } answers { log += "evictHttpCache" }
        every { ops.deleteImageBackups() } answers { log += "deleteImageBackups" }
        every { ops.cancelAllNotifications() } answers { log += "cancelNotifications" }
        every { ops.deleteFcmToken() } answers { log += "deleteFcmToken" }
        every { ops.resetAnalytics() } answers { log += "resetAnalytics" }
        coEvery { alertRepository.clearPendingAlerts() } coAnswers { log += "clearPendingAlerts" }
        coEvery { alertRepository.clearCooldowns() } coAnswers { log += "clearCooldowns" }
        coEvery { customWordRepository.clearCache() } coAnswers { log += "clearWordCache" }
        every { screenTimeRulesRepository.clearCache() } answers { log += "clearRulesCache" }
        every { imageHasher.clearCache() } answers { log += "clearImageHasher" }
        every { textHasher.clearCache() } answers { log += "clearTextHasher" }
        every { preferencesManager.isContentFilteringEnabled = false } answers { log += "filteringOff" }
        every { preferencesManager.clearAll() } answers { prefsCleared = true; log += "clearPrefs" }
        every { preferencesManager.isChild } answers { !prefsCleared }
        every { state.markTeardownInProgress(any()) } answers { log += "marker" }
        every { state.raiseNotice(any(), any()) } answers { log += "notice" }
        every { state.completeTeardown(any(), any()) } answers { log += "complete" }
        every { tokenManager.clearTokensDueToAccountDeletion(any()) } answers { log += "clearTokens" }

        manager = AccountTeardownManager(
            ops, alertRepository, customWordRepository, screenTimeRulesRepository,
            imageHasher, textHasher, preferencesManager, tokenManager, state
        )
    }

    @Test
    fun `steps run in the documented order`() = runBlocking {
        manager.teardown(TeardownReason.ACCOUNT_DELETED)

        val expected = listOf(
            "marker",
            "filteringOff",
            "cancelAllWork", "cancelAlarm",
            "stopMonitoring", "stopVpn",
            "evictHttpCache", "clearPendingAlerts", "clearCooldowns", "clearWordCache",
            "clearRulesCache", "clearImageHasher", "clearTextHasher", "deleteImageBackups",
            "cancelNotifications", "deleteFcmToken", "resetAnalytics",
            "clearPrefs", "notice", "clearTokens",
            "cancelAllWork",
            "complete"
        )
        assertEquals(expected, log.toList())
    }

    @Test
    fun `tokens are cleared after prefs and before the final cancel`() = runBlocking {
        manager.teardown(TeardownReason.ACCOUNT_DELETED)
        val snapshot = log.toList()
        assertTrue(snapshot.indexOf("clearPrefs") < snapshot.indexOf("clearTokens"))
        assertTrue(snapshot.indexOf("clearTokens") < snapshot.lastIndexOf("cancelAllWork"))
        assertEquals("complete", snapshot.last())
    }

    @Test
    fun `the notice flag is durable before the account deleted event can be emitted`() = runBlocking {
        manager.teardown(TeardownReason.ACCOUNT_DELETED)
        val snapshot = log.toList()
        assertTrue(snapshot.indexOf("notice") < snapshot.indexOf("clearTokens"))
        verify { state.raiseNotice(showNotice = true, childDevice = true) }
    }

    @Test
    fun `account deleted event is emitted only for ACCOUNT_DELETED`() = runBlocking {
        manager.teardown(TeardownReason.ACCOUNT_DELETED)
        verify(exactly = 1) { tokenManager.clearTokensDueToAccountDeletion(true) }
        verify(exactly = 0) { tokenManager.clearTokensDueToAccountDeletion(false) }

        manager.teardown(TeardownReason.SESSION_GONE_AFTER_MARKER)
        verify(exactly = 1) { tokenManager.clearTokensDueToAccountDeletion(false) }
    }

    @Test
    fun `notice is shown only for a confirmed deletion`() = runBlocking {
        manager.teardown(TeardownReason.ACCOUNT_DELETED)
        verify { state.completeTeardown(showNotice = true, childDevice = true) }

        prefsCleared = false
        manager.teardown(TeardownReason.SESSION_GONE_AFTER_MARKER)
        verify { state.completeTeardown(showNotice = false, childDevice = true) }
    }

    @Test
    fun `a step that throws does not stop the steps after it`() = runBlocking {
        every { ops.stopVpn() } answers { log += "stopVpn"; throw IllegalStateException("boom") }
        coEvery { alertRepository.clearPendingAlerts() } coAnswers {
            log += "clearPendingAlerts"; throw RuntimeException("room closed")
        }

        manager.teardown(TeardownReason.ACCOUNT_DELETED)

        val snapshot = log.toList()
        assertTrue(snapshot.contains("stopVpn"))
        assertTrue(snapshot.indexOf("evictHttpCache") > snapshot.indexOf("stopVpn"))
        assertTrue(snapshot.indexOf("clearCooldowns") > snapshot.indexOf("clearPendingAlerts"))
        assertTrue(snapshot.contains("clearPrefs"))
        assertTrue(snapshot.contains("clearTokens"))
        assertEquals("complete", snapshot.last())
    }

    @Test
    fun `a second call is harmless`() = runBlocking {
        manager.teardown(TeardownReason.ACCOUNT_DELETED)
        manager.teardown(TeardownReason.ACCOUNT_DELETED)
        assertEquals(2, log.count { it == "complete" })
    }

    @Test
    fun `cancelling the caller midway still completes every step`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        coEvery { alertRepository.clearPendingAlerts() } coAnswers {
            reached.complete(Unit)
            gate.await()
            log += "clearPendingAlerts"
        }

        val job = launch(Dispatchers.Default) { manager.teardown(TeardownReason.ACCOUNT_DELETED) }
        withTimeout(5_000) { reached.await() }

        job.cancel()
        delay(50)
        gate.complete(Unit)
        job.join()

        val snapshot = log.toList()
        assertTrue("teardown must still finish after cancel: $snapshot", snapshot.contains("complete"))
        assertTrue(snapshot.contains("clearTokens"))
        assertEquals("complete", snapshot.last())
    }

    @Test
    fun `filtering pref is switched off before the vpn is stopped`() = runBlocking {
        manager.teardown(TeardownReason.ACCOUNT_DELETED)
        val snapshot = log.toList()
        assertTrue(snapshot.indexOf("filteringOff") < snapshot.indexOf("stopVpn"))
        assertTrue(snapshot.indexOf("filteringOff") < snapshot.indexOf("stopMonitoring"))
    }

    @Test
    fun `a child device raises the uninstall note and switches filtering off before stopping the vpn`() = runBlocking {
        manager.teardown(TeardownReason.ACCOUNT_DELETED)

        verify { state.completeTeardown(showNotice = true, childDevice = true) }
        val snapshot = log.toList()
        assertTrue(snapshot.indexOf("filteringOff") < snapshot.indexOf("stopVpn"))
    }

    @Test
    fun `a parent device does not raise the child uninstall note`() = runBlocking {
        every { preferencesManager.isChild } returns false

        manager.teardown(TeardownReason.ACCOUNT_DELETED)

        verify { state.completeTeardown(showNotice = true, childDevice = false) }
    }

    @Test
    fun `role is captured before the prefs are wiped`() = runBlocking {
        manager.teardown(TeardownReason.ACCOUNT_DELETED)
        verify { state.completeTeardown(showNotice = true, childDevice = true) }
        assertFalse(preferencesManager.isChild)
    }
}
