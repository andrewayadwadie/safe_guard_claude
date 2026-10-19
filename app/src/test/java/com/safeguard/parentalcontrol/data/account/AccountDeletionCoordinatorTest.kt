package com.safeguard.parentalcontrol.data.account

import com.safeguard.parentalcontrol.data.model.DeleteAccountRequest
import com.safeguard.parentalcontrol.data.model.User
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AuthRepository
import com.safeguard.parentalcontrol.data.repository.DeletionError
import com.safeguard.parentalcontrol.data.repository.FamilyRepository
import com.safeguard.parentalcontrol.util.AccountDeletionState
import com.safeguard.parentalcontrol.util.FakeSharedPreferences
import com.safeguard.parentalcontrol.util.PreferencesManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionCoordinatorTest {

    private lateinit var authRepository: AuthRepository
    private lateinit var familyRepository: FamilyRepository
    private lateinit var teardownManager: AccountTeardownManager
    private lateinit var state: AccountDeletionState
    private lateinit var prefs: PreferencesManager

    private val request = DeleteAccountRequest.forPassword("secret")

    @Before
    fun setup() {
        authRepository = mockk()
        familyRepository = mockk()
        teardownManager = mockk(relaxed = true)
        state = AccountDeletionState(FakeSharedPreferences.contextBackedBy(FakeSharedPreferences()))
        prefs = mockk(relaxed = true)
        every { prefs.isParent } returns true
        every { prefs.hasLinkedParent } returns false
        every { prefs.isLoggedIn } returns true
    }

    private fun TestScope.newCoordinator() = AccountDeletionCoordinator(
        authRepository, familyRepository, teardownManager, state, prefs,
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
    )

    private fun error(code: Int?) = NetworkResult.Error("msg", code)

    // ---------------------------------------------------------------- self delete

    @Test
    fun `two calls while the first is in flight make exactly one repository call`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { authRepository.deleteAccount(any()) } coAnswers {
            gate.await()
            NetworkResult.Success(Unit)
        }
        val coordinator = newCoordinator()

        coordinator.deleteOwnAccount(request)
        coordinator.deleteOwnAccount(request)
        advanceUntilIdle()
        assertEquals(DeletionState.InFlight, coordinator.selfState.value)

        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { authRepository.deleteAccount(any()) }
        assertEquals(DeletionState.Succeeded, coordinator.selfState.value)
    }

    @Test
    fun `success sets the marker before the call, tears down, and reports Succeeded`() = runTest {
        var markerSeenDuringCall: Boolean? = null
        coEvery { authRepository.deleteAccount(any()) } coAnswers {
            markerSeenDuringCall = state.pending() != null
            NetworkResult.Success(Unit)
        }
        val coordinator = newCoordinator()

        coordinator.deleteOwnAccount(request)
        advanceUntilIdle()

        assertEquals("marker must exist before the request is sent", true, markerSeenDuringCall)
        coVerify(exactly = 1) { teardownManager.teardown(TeardownReason.ACCOUNT_DELETED) }
        assertEquals(DeletionState.Succeeded, coordinator.selfState.value)
    }

    @Test
    fun `400 clears the marker and reports WrongPassword without a teardown`() = runTest {
        coEvery { authRepository.deleteAccount(any()) } returns error(400)
        val coordinator = newCoordinator()

        coordinator.deleteOwnAccount(request)
        advanceUntilIdle()

        assertNull(state.pending())
        assertEquals(DeletionState.Failed(DeletionError.WrongPassword), coordinator.selfState.value)
        coVerify(exactly = 0) { teardownManager.teardown(any()) }
    }

    @Test
    fun `429 reports RateLimited`() = runTest {
        coEvery { authRepository.deleteAccount(any()) } returns error(429)
        val coordinator = newCoordinator()

        coordinator.deleteOwnAccount(request)
        advanceUntilIdle()

        assertEquals(DeletionState.Failed(DeletionError.RateLimited(null)), coordinator.selfState.value)
        assertNull(state.pending())
    }

    @Test
    fun `cancelling a collector does not cancel the deletion`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { authRepository.deleteAccount(any()) } coAnswers {
            gate.await()
            NetworkResult.Success(Unit)
        }
        val coordinator = newCoordinator()

        val collector = launch { coordinator.selfState.collect { } }
        coordinator.deleteOwnAccount(request)
        advanceUntilIdle()
        collector.cancel()

        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { teardownManager.teardown(TeardownReason.ACCOUNT_DELETED) }
        assertEquals(DeletionState.Succeeded, coordinator.selfState.value)
    }

    @Test
    fun `network error keeps the marker, and a retry that gets 401 recovers instead of claiming success`() = runTest {
        coEvery { authRepository.deleteAccount(any()) } returns error(null)
        val coordinator = newCoordinator()

        coordinator.deleteOwnAccount(request)
        advanceUntilIdle()

        assertEquals(DeletionState.Failed(DeletionError.Network), coordinator.selfState.value)
        assertNotNull("the response may have been lost, so the marker must stay", state.pending())
        coVerify(exactly = 0) { teardownManager.teardown(any()) }

        coordinator.acknowledgeSelf()
        coEvery { authRepository.deleteAccount(any()) } returns error(401)
        coordinator.deleteOwnAccount(request)
        advanceUntilIdle()

        coVerify(exactly = 1) { teardownManager.teardown(TeardownReason.SESSION_GONE_AFTER_MARKER) }
        coVerify(exactly = 0) { teardownManager.teardown(TeardownReason.ACCOUNT_DELETED) }
        assertEquals(DeletionState.Failed(DeletionError.SessionOver), coordinator.selfState.value)
    }

    @Test
    fun `403 clears the marker, reports SupervisedChild and never tears anything down`() = runTest {
        every { prefs.isParent } returns false
        every { prefs.hasLinkedParent } returns true
        coEvery { authRepository.deleteAccount(any()) } returns error(403)
        val coordinator = newCoordinator()

        coordinator.deleteOwnAccount(request)
        advanceUntilIdle()

        assertNull(state.pending())
        assertEquals(DeletionState.Failed(DeletionError.SupervisedChild), coordinator.selfState.value)
        coVerify(exactly = 0) { teardownManager.teardown(any()) }
    }

    @Test
    fun `401 on a linked child never stops protection`() = runTest {
        every { prefs.isParent } returns false
        every { prefs.hasLinkedParent } returns true
        coEvery { authRepository.deleteAccount(any()) } returns error(401)
        val coordinator = newCoordinator()

        coordinator.deleteOwnAccount(request)
        advanceUntilIdle()

        coVerify(exactly = 0) { teardownManager.teardown(any()) }
        assertNull(state.pending())
        assertEquals(DeletionState.Failed(DeletionError.SessionOver), coordinator.selfState.value)
    }

    @Test
    fun `attachSelf resets a terminal state but never an in-flight one`() = runTest {
        coEvery { authRepository.deleteAccount(any()) } returns NetworkResult.Success(Unit)
        val coordinator = newCoordinator()
        coordinator.deleteOwnAccount(request)
        advanceUntilIdle()
        assertEquals(DeletionState.Succeeded, coordinator.selfState.value)

        coordinator.attachSelf()
        assertEquals(DeletionState.Idle, coordinator.selfState.value)

        val gate = CompletableDeferred<Unit>()
        coEvery { authRepository.deleteAccount(any()) } coAnswers {
            gate.await()
            NetworkResult.Success(Unit)
        }
        coordinator.deleteOwnAccount(request)
        advanceUntilIdle()
        coordinator.attachSelf()
        assertEquals(DeletionState.InFlight, coordinator.selfState.value)
    }

    @Test
    fun `acknowledgeSelf resets Failed and Succeeded but leaves InFlight`() = runTest {
        coEvery { authRepository.deleteAccount(any()) } returns error(400)
        val coordinator = newCoordinator()
        coordinator.deleteOwnAccount(request)
        advanceUntilIdle()
        coordinator.acknowledgeSelf()
        assertEquals(DeletionState.Idle, coordinator.selfState.value)
    }

    // ---------------------------------------------------------------- child deletion

    private val password = ChildReauth.Password("parent-secret")

    @Test
    fun `wrong parent password on re-auth is WrongPassword and nothing is deleted`() = runTest {
        coEvery { authRepository.reauthenticateWithPassword(any()) } returns error(401)
        val coordinator = newCoordinator()

        coordinator.deleteChildAccount(7, password)
        advanceUntilIdle()

        assertEquals(DeletionState.Failed(DeletionError.WrongPassword), coordinator.childStates.value[7])
        coVerify(exactly = 0) { familyRepository.deleteChildAccount(any()) }
    }

    @Test
    fun `an account mismatch on re-auth is AccountMismatch and nothing is deleted`() = runTest {
        coEvery { authRepository.reauthenticateWithPassword(any()) } returns
            error(DeletionError.CODE_ACCOUNT_MISMATCH)
        val coordinator = newCoordinator()

        coordinator.deleteChildAccount(7, password)
        advanceUntilIdle()

        assertEquals(DeletionState.Failed(DeletionError.AccountMismatch), coordinator.childStates.value[7])
        coVerify(exactly = 0) { familyRepository.deleteChildAccount(any()) }
    }

    @Test
    fun `re-auth ok and delete 2xx is Succeeded`() = runTest {
        coEvery { authRepository.reauthenticateWithPassword("parent-secret") } returns NetworkResult.Success(Unit)
        coEvery { familyRepository.deleteChildAccount(7) } returns NetworkResult.Success(Unit)
        val coordinator = newCoordinator()

        coordinator.deleteChildAccount(7, password)
        advanceUntilIdle()

        assertEquals(DeletionState.Succeeded, coordinator.childStates.value[7])
    }

    @Test
    fun `google re-auth uses the id token`() = runTest {
        coEvery { authRepository.reauthenticateWithGoogle("id-token") } returns NetworkResult.Success(Unit)
        coEvery { familyRepository.deleteChildAccount(7) } returns NetworkResult.Success(Unit)
        val coordinator = newCoordinator()

        coordinator.deleteChildAccount(7, ChildReauth.Google("id-token"))
        advanceUntilIdle()

        coVerify(exactly = 1) { authRepository.reauthenticateWithGoogle("id-token") }
        coVerify(exactly = 0) { authRepository.reauthenticateWithPassword(any()) }
        assertEquals(DeletionState.Succeeded, coordinator.childStates.value[7])
    }

    @Test
    fun `re-auth ok and 404 is ChildNotFound`() = runTest {
        coEvery { authRepository.reauthenticateWithPassword(any()) } returns NetworkResult.Success(Unit)
        coEvery { familyRepository.deleteChildAccount(7) } returns error(404)
        val coordinator = newCoordinator()

        coordinator.deleteChildAccount(7, password)
        advanceUntilIdle()

        assertEquals(DeletionState.Failed(DeletionError.ChildNotFound), coordinator.childStates.value[7])
    }

    @Test
    fun `two calls for the same child while in flight make one re-auth and one delete`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { authRepository.reauthenticateWithPassword(any()) } coAnswers {
            gate.await()
            NetworkResult.Success(Unit)
        }
        coEvery { familyRepository.deleteChildAccount(7) } returns NetworkResult.Success(Unit)
        val coordinator = newCoordinator()

        coordinator.deleteChildAccount(7, password)
        coordinator.deleteChildAccount(7, password)
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { authRepository.reauthenticateWithPassword(any()) }
        coVerify(exactly = 1) { familyRepository.deleteChildAccount(7) }
    }

    @Test
    fun `calls for different children are independent`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { authRepository.reauthenticateWithPassword(any()) } coAnswers {
            gate.await()
            NetworkResult.Success(Unit)
        }
        coEvery { familyRepository.deleteChildAccount(any()) } returns NetworkResult.Success(Unit)
        val coordinator = newCoordinator()

        coordinator.deleteChildAccount(7, password)
        coordinator.deleteChildAccount(8, password)
        advanceUntilIdle()

        assertEquals(DeletionState.InFlight, coordinator.childStates.value[7])
        assertEquals(DeletionState.InFlight, coordinator.childStates.value[8])

        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { familyRepository.deleteChildAccount(7) }
        coVerify(exactly = 1) { familyRepository.deleteChildAccount(8) }
    }

    @Test
    fun `acknowledging one child leaves the others alone`() = runTest {
        coEvery { authRepository.reauthenticateWithPassword(any()) } returns error(401)
        val coordinator = newCoordinator()
        coordinator.deleteChildAccount(7, password)
        coordinator.deleteChildAccount(8, password)
        advanceUntilIdle()

        coordinator.acknowledgeChild(7)

        assertNull(coordinator.childStates.value[7])
        assertNotNull(coordinator.childStates.value[8])
    }

    @Test
    fun `a child deletion never runs a teardown and never writes the marker`() = runTest {
        coEvery { authRepository.reauthenticateWithPassword(any()) } returns NetworkResult.Success(Unit)
        coEvery { familyRepository.deleteChildAccount(7) } returns NetworkResult.Success(Unit)
        val coordinator = newCoordinator()

        coordinator.deleteChildAccount(7, password)
        advanceUntilIdle()

        coVerify(exactly = 0) { teardownManager.teardown(any()) }
        assertNull(state.pending())
        assertEquals(DeletionState.Idle, coordinator.selfState.value)
    }

    @Test
    fun `ChildReauth toString never contains the secret`() {
        assertFalse(ChildReauth.Password("parent-secret").toString().contains("parent-secret"))
        assertFalse(ChildReauth.Google("id-token-value").toString().contains("id-token-value"))
    }

    // ---------------------------------------------------------------- recovery on launch

    @Test
    fun `no marker and no teardown flag means nothing to recover`() = runTest {
        val coordinator = newCoordinator()
        assertFalse(coordinator.hasPendingRecovery())
        coordinator.resumePendingIfAny()
        coVerify(exactly = 0) { authRepository.getCurrentUser() }
        coVerify(exactly = 0) { teardownManager.teardown(any()) }
    }

    @Test
    fun `marker and a live account clears the marker without a teardown`() = runTest {
        state.markPending(roleWasParent = true, hadLinkedParent = false)
        coEvery { authRepository.getCurrentUser() } returns NetworkResult.Success(mockk<User>(relaxed = true))
        val coordinator = newCoordinator()

        assertTrue(coordinator.hasPendingRecovery())
        coordinator.resumePendingIfAny()

        assertNull(state.pending())
        coVerify(exactly = 0) { teardownManager.teardown(any()) }
    }

    @Test
    fun `marker and 401 on a parent runs the neutral teardown`() = runTest {
        state.markPending(roleWasParent = true, hadLinkedParent = false)
        coEvery { authRepository.getCurrentUser() } returns error(401)
        val coordinator = newCoordinator()

        coordinator.resumePendingIfAny()

        coVerify(exactly = 1) { teardownManager.teardown(TeardownReason.SESSION_GONE_AFTER_MARKER) }
        coVerify(exactly = 0) { teardownManager.teardown(TeardownReason.ACCOUNT_DELETED) }
    }

    @Test
    fun `marker and 401 on an unlinked child runs the neutral teardown`() = runTest {
        state.markPending(roleWasParent = false, hadLinkedParent = false)
        coEvery { authRepository.getCurrentUser() } returns error(401)
        val coordinator = newCoordinator()

        coordinator.resumePendingIfAny()

        coVerify(exactly = 1) { teardownManager.teardown(TeardownReason.SESSION_GONE_AFTER_MARKER) }
    }

    @Test
    fun `marker and 401 on a linked child leaves protection alone and clears the marker`() = runTest {
        state.markPending(roleWasParent = false, hadLinkedParent = true)
        coEvery { authRepository.getCurrentUser() } returns error(401)
        val coordinator = newCoordinator()

        coordinator.resumePendingIfAny()

        coVerify(exactly = 0) { teardownManager.teardown(any()) }
        assertNull(state.pending())
    }

    @Test
    fun `marker and a network error keeps the marker and touches nothing`() = runTest {
        state.markPending(roleWasParent = true, hadLinkedParent = false)
        coEvery { authRepository.getCurrentUser() } returns error(null)
        val coordinator = newCoordinator()

        coordinator.resumePendingIfAny()

        assertNotNull(state.pending())
        assertTrue(coordinator.hasPendingRecovery())
        coVerify(exactly = 0) { teardownManager.teardown(any()) }
    }

    @Test
    fun `a leftover marker with no signed-in session is discarded without a request`() = runTest {
        every { prefs.isLoggedIn } returns false
        state.markPending(roleWasParent = true, hadLinkedParent = false)
        val coordinator = newCoordinator()

        coordinator.resumePendingIfAny()

        assertNull(state.pending())
        coVerify(exactly = 0) { authRepository.getCurrentUser() }
        coVerify(exactly = 0) { teardownManager.teardown(any()) }
    }

    @Test
    fun `an interrupted teardown resumes with the same reason`() = runTest {
        state.markTeardownInProgress(TeardownReason.ACCOUNT_DELETED)
        val coordinator = newCoordinator()

        assertTrue(coordinator.hasPendingRecovery())
        coordinator.resumePendingIfAny()

        coVerify(exactly = 1) { teardownManager.teardown(TeardownReason.ACCOUNT_DELETED) }
        coVerify(exactly = 0) { authRepository.getCurrentUser() }
    }
}
