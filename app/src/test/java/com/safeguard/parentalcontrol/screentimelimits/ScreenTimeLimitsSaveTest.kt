package com.safeguard.parentalcontrol.screentimelimits

import android.content.Context
import com.google.gson.Gson
import com.safeguard.parentalcontrol.MainDispatcherRule
import com.safeguard.parentalcontrol.data.model.ScreenTimeRule
import com.safeguard.parentalcontrol.data.model.ScreenTimeRuleUpdateRequest
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.ScreenTimeRepository
import com.safeguard.parentalcontrol.data.repository.ScreenTimeRulesRepository
import com.safeguard.parentalcontrol.presentation.screentimelimits.ScreenTimeLimitsViewModel
import com.safeguard.parentalcontrol.util.LocaleHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Date

/**
 * The parent-side save path.
 *
 * The defect these tests exist for: a lock toggled on this screen changed local UI state and
 * nothing else, so the parent saw a lock the server had never been told about. The screen now
 * saves per control, which brings two further obligations worth pinning down — a save carries
 * only the control that changed, and an emptied collection is transmitted rather than omitted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenTimeLimitsSaveTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val deviceId = 42

    private lateinit var rulesRepository: ScreenTimeRulesRepository
    private lateinit var screenTimeRepository: ScreenTimeRepository
    private val context = mockk<Context>(relaxed = true)

    /** One captured PUT, named so an assertion reads like the wire format it is about. */
    private data class Put(
        val deviceId: Int,
        val dailyLimit: Int?,
        val bedtimeEnabled: Boolean?,
        val bedtimeStart: String?,
        val bedtimeEnd: String?,
        val studyTimeEnabled: Boolean?,
        val studyTimeStart: String?,
        val studyTimeEnd: String?,
        val studyTimeAllowedApps: List<String>?,
        val isDeviceLocked: Boolean?,
        val deviceLockedMessage: String?,
        val appLimits: Map<String, Int>?,
        val blockedApps: List<String>?,
        val isActive: Boolean?
    )

    private val puts = mutableListOf<Put>()
    private var putResult: NetworkResult<ScreenTimeRule> = NetworkResult.Success(rule())

    private fun rule(
        blockedApps: List<String>? = listOf("com.facebook.katana"),
        isActive: Boolean = true
    ) = ScreenTimeRule(
        id = 7,
        deviceId = 42,
        dailyLimit = 7200,
        bedtimeEnabled = true,
        bedtimeStart = "21:00",
        bedtimeEnd = "07:00",
        appLimits = mapOf("com.google.android.youtube" to 1800),
        blockedApps = blockedApps,
        isActive = isActive,
        createdAt = Date(0),
        updatedAt = Date(0)
    )

    @Suppress("UNCHECKED_CAST")
    @Before
    fun setup() {
        puts.clear()
        putResult = NetworkResult.Success(rule())

        rulesRepository = mockk()
        screenTimeRepository = mockk()

        coEvery { rulesRepository.getScreenTimeRules(deviceId) } returns
            NetworkResult.Success(rule())
        coEvery { screenTimeRepository.getAppUsageLogs(any(), any(), any()) } returns
            NetworkResult.Success(emptyList())

        coEvery {
            rulesRepository.updateScreenTimeRules(
                any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()
            )
        } answers {
            puts += Put(
                deviceId = args[0] as Int,
                dailyLimit = args[1] as Int?,
                bedtimeEnabled = args[2] as Boolean?,
                bedtimeStart = args[3] as String?,
                bedtimeEnd = args[4] as String?,
                studyTimeEnabled = args[5] as Boolean?,
                studyTimeStart = args[6] as String?,
                studyTimeEnd = args[7] as String?,
                studyTimeAllowedApps = args[8] as List<String>?,
                isDeviceLocked = args[9] as Boolean?,
                deviceLockedMessage = args[10] as String?,
                appLimits = args[11] as Map<String, Int>?,
                blockedApps = args[12] as List<String>?,
                isActive = args[13] as Boolean?
            )
            putResult
        }

        mockkObject(LocaleHelper)
        every { LocaleHelper.localizedContext(any()) } returns context
        every { context.getString(any(), *anyVararg()) } returns "error"
    }

    @After
    fun tearDown() {
        unmockkObject(LocaleHelper)
    }

    private fun viewModel() =
        ScreenTimeLimitsViewModel(rulesRepository, screenTimeRepository, context)

    /** A view model with rules already loaded, so every test starts from confirmed state. */
    private fun TestScope.loaded(): ScreenTimeLimitsViewModel {
        val vm = viewModel()
        vm.loadRules(deviceId)
        advanceUntilIdle()
        puts.clear()
        return vm
    }

    @Test
    fun `locking an app saves without any save action`() = runTest {
        val vm = loaded()

        vm.addBlockedApp("com.instagram.android")
        advanceUntilIdle()

        assertEquals("exactly one request for one change", 1, puts.size)
        assertEquals(
            listOf("com.facebook.katana", "com.instagram.android"),
            puts[0].blockedApps
        )
        assertEquals(deviceId, puts[0].deviceId)
    }

    @Test
    fun `a lock save carries only its own field`() = runTest {
        val vm = loaded()

        vm.addBlockedApp("com.instagram.android")
        advanceUntilIdle()

        val put = puts.single()
        // Absent means "leave unchanged" on the wire. Anything this screen did not edit must
        // stay absent, otherwise a lock change would push a stale copy of every other control.
        assertNull("daily_limit must not be sent", put.dailyLimit)
        assertNull("bedtime_enabled must not be sent", put.bedtimeEnabled)
        assertNull("bedtime_start must not be sent", put.bedtimeStart)
        assertNull("bedtime_end must not be sent", put.bedtimeEnd)
        assertNull("app_limits must not be sent", put.appLimits)
        assertNull("study_time_allowed_apps must not be sent", put.studyTimeAllowedApps)
        assertNull("device_locked_message must not be sent", put.deviceLockedMessage)
        assertNotNull("blocked_apps is the field that changed", put.blockedApps)
    }

    @Test
    fun `a lock asserts the active state only when the master switch is already on`() = runTest {
        val vm = loaded()

        vm.addBlockedApp("com.instagram.android")
        advanceUntilIdle()
        assertEquals("master switch on: assert it explicitly", true, puts.single().isActive)

        // Parent turns enforcement off, then locks another app.
        puts.clear()
        coEvery { rulesRepository.getScreenTimeRules(deviceId) } returns
            NetworkResult.Success(rule(isActive = false))
        putResult = NetworkResult.Success(rule(isActive = false))
        vm.setRulesActive(false)
        advanceUntilIdle()
        puts.clear()

        vm.addBlockedApp("com.snapchat.android")
        advanceUntilIdle()

        assertNull(
            "a lock must never re-enable a policy the parent switched off",
            puts.single().isActive
        )
    }

    @Test
    fun `three rapid toggles of one control issue one request for the settled value`() = runTest {
        val vm = loaded()

        vm.addBlockedApp("com.instagram.android")
        vm.removeBlockedApp("com.instagram.android")
        vm.addBlockedApp("com.instagram.android")
        advanceUntilIdle()

        assertEquals("rapid changes coalesce", 1, puts.size)
        assertTrue(
            "the settled value is what reaches the server",
            puts[0].blockedApps!!.contains("com.instagram.android")
        )
        assertEquals(
            "and the screen agrees with it",
            puts[0].blockedApps,
            vm.uiState.value.blockedApps
        )
    }

    @Test
    fun `two controls changed together each save their own field`() = runTest {
        val vm = loaded()

        vm.addBlockedApp("com.instagram.android")
        vm.setBedtimeEnabled(false)
        advanceUntilIdle()

        assertEquals(2, puts.size)
        val lock = puts.single { it.blockedApps != null }
        val bedtime = puts.single { it.bedtimeEnabled != null }
        assertNull("the lock request left bedtime alone", lock.bedtimeEnabled)
        assertNull("the bedtime request left the locks alone", bedtime.blockedApps)
    }

    @Test
    fun `a failed save reverts the control and reports the error`() = runTest {
        val vm = loaded()
        val before = vm.uiState.value.blockedApps
        putResult = NetworkResult.Error("offline", -1)

        vm.addBlockedApp("com.instagram.android")
        advanceUntilIdle()

        assertEquals("the control returns to the confirmed value", before, vm.uiState.value.blockedApps)
        assertEquals("offline", vm.uiState.value.error)
        assertTrue("and nothing is left showing progress", vm.uiState.value.savingFields.isEmpty())
    }

    @Test
    fun `a failed save does not discard an unrelated change made meanwhile`() = runTest {
        val vm = loaded()
        putResult = NetworkResult.Error("offline", -1)

        vm.addBlockedApp("com.instagram.android")
        vm.setDeviceLockedMessage("back in 10")
        advanceUntilIdle()

        assertFalse(
            "the blocked list rolled back",
            vm.uiState.value.blockedApps.contains("com.instagram.android")
        )
        assertEquals(
            "the unrelated edit survived the rollback",
            "back in 10",
            vm.uiState.value.deviceLockedMessage
        )
    }

    @Test
    fun `unlocking the last app sends an explicit empty list`() = runTest {
        val vm = loaded()

        vm.removeBlockedApp("com.facebook.katana")
        advanceUntilIdle()

        val put = puts.single()
        assertNotNull("the key must be present, not omitted", put.blockedApps)
        assertEquals(emptyList<String>(), put.blockedApps)
    }

    @Test
    fun `an emptied collection serialises with its key present`() {
        val gson = Gson()

        val blocked = gson.toJson(ScreenTimeRuleUpdateRequest(blockedApps = emptyList()))
        assertTrue("blocked_apps=[] must reach the wire: $blocked", blocked.contains("\"blocked_apps\":[]"))
        assertFalse("and carry nothing else: $blocked", blocked.contains("daily_limit"))

        val limits = gson.toJson(ScreenTimeRuleUpdateRequest(appLimits = emptyMap()))
        assertTrue("app_limits={}: $limits", limits.contains("\"app_limits\":{}"))

        val study = gson.toJson(ScreenTimeRuleUpdateRequest(studyTimeAllowedApps = emptyList()))
        assertTrue("study_time_allowed_apps=[]: $study", study.contains("\"study_time_allowed_apps\":[]"))

        // The converse, and the reason the empty cases above matter: a null field is omitted
        // entirely, which the backend reads as "leave unchanged".
        val nothing = gson.toJson(ScreenTimeRuleUpdateRequest(blockedApps = null))
        assertFalse("a null field is omitted: $nothing", nothing.contains("blocked_apps"))
    }

    @Test
    fun `the master enforcement switch saves on change`() = runTest {
        val vm = loaded()

        vm.setRulesActive(false)
        advanceUntilIdle()

        val put = puts.single()
        assertEquals(false, put.isActive)
        assertNull("and carries nothing else", put.blockedApps)
    }
}
