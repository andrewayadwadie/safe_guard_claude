package com.safeguard.parentalcontrol.presentation.deleteaccount

import android.content.Context
import com.safeguard.parentalcontrol.MainDispatcherRule
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.account.AccountDeletionCoordinator
import com.safeguard.parentalcontrol.data.account.DeletionState
import com.safeguard.parentalcontrol.data.model.DeleteAccountRequest
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AuthRepository
import com.safeguard.parentalcontrol.data.repository.DeletionError
import com.safeguard.parentalcontrol.data.repository.FamilyRepository
import com.safeguard.parentalcontrol.util.ImageBlurManager
import com.safeguard.parentalcontrol.util.LocaleHelper
import com.safeguard.parentalcontrol.util.PreferencesManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAccountViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var coordinator: AccountDeletionCoordinator
    private lateinit var authRepository: AuthRepository
    private lateinit var familyRepository: FamilyRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var imageBlurManager: ImageBlurManager
    private val context = mockk<Context>(relaxed = true)
    private val selfFlow = MutableStateFlow<DeletionState>(DeletionState.Idle)

    private fun text(resId: Int) = "res:$resId"

    @Before
    fun setup() {
        coordinator = mockk(relaxed = true)
        authRepository = mockk()
        familyRepository = mockk()
        preferencesManager = mockk(relaxed = true)
        imageBlurManager = mockk()

        every { coordinator.selfState } returns selfFlow
        every { coordinator.acknowledgeSelf() } answers {
            selfFlow.update { if (it is DeletionState.Failed || it is DeletionState.Succeeded) DeletionState.Idle else it }
        }
        every { preferencesManager.isParent } returns true
        coEvery { authRepository.getCurrentUser() } returns NetworkResult.Error("offline", null)
        coEvery { familyRepository.getLinkedChildren() } returns NetworkResult.Success(emptyList())
        every { imageBlurManager.getPendingReviews() } returns emptyList()

        mockkObject(LocaleHelper)
        every { LocaleHelper.localizedContext(any()) } returns context
        every { context.getString(any(), *anyVararg()) } answers { text(firstArg()) }
    }

    @After
    fun tearDown() {
        unmockkObject(LocaleHelper)
    }

    private fun viewModel() = DeleteAccountViewModel(
        coordinator, authRepository, familyRepository, preferencesManager, imageBlurManager,
        context, mainDispatcherRule.testDispatcher
    )

    @Test
    fun `a new view model resets any leftover terminal state`() = runTest {
        viewModel()
        verify { coordinator.attachSelf() }
    }

    @Test
    fun `canSubmit is false for an empty password and true once one is typed`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.canSubmit)

        vm.onCredentialChange("x")
        assertTrue(vm.uiState.value.canSubmit)
    }

    @Test
    fun `in DELETE mode only the exact upper-case word enables submit`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.switchMode()
        assertEquals(ConfirmMode.GOOGLE_DELETE, vm.uiState.value.mode)

        vm.onCredentialChange("delete")
        assertFalse(vm.uiState.value.canSubmit)
        vm.onCredentialChange("DELETE ")
        assertFalse(vm.uiState.value.canSubmit)
        vm.onCredentialChange("DELETE")
        assertTrue(vm.uiState.value.canSubmit)
    }

    @Test
    fun `a Google account from auth me pre-selects DELETE mode`() = runTest {
        val user = mockk<com.safeguard.parentalcontrol.data.model.User>(relaxed = true)
        every { user.authProvider } returns com.safeguard.parentalcontrol.data.model.AuthProvider.GOOGLE
        coEvery { authRepository.getCurrentUser() } returns NetworkResult.Success(user)

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(ConfirmMode.GOOGLE_DELETE, vm.uiState.value.mode)
    }

    @Test
    fun `requesting the final confirmation does not call the coordinator, confirming does exactly once`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onCredentialChange("secret")

        vm.onDeleteClicked()
        assertTrue(vm.uiState.value.showFinalConfirm)
        verify(exactly = 0) { coordinator.deleteOwnAccount(any()) }

        vm.onFinalConfirm()
        verify(exactly = 1) { coordinator.deleteOwnAccount(DeleteAccountRequest.forPassword("secret")) }
        assertFalse(vm.uiState.value.showFinalConfirm)
    }

    @Test
    fun `DELETE mode sends the confirmation body`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.switchMode()
        vm.onCredentialChange("DELETE")
        vm.onDeleteClicked()
        vm.onFinalConfirm()
        verify(exactly = 1) { coordinator.deleteOwnAccount(DeleteAccountRequest.forGoogle()) }
    }

    @Test
    fun `an empty credential can never reach the coordinator`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onDeleteClicked()
        assertFalse(vm.uiState.value.showFinalConfirm)
        vm.onFinalConfirm()

        verify(exactly = 0) { coordinator.deleteOwnAccount(any()) }
    }

    @Test
    fun `WrongPassword is shown inline and never as a snackbar error`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        selfFlow.value = DeletionState.Failed(DeletionError.WrongPassword)
        advanceUntilIdle()

        assertEquals(text(R.string.delete_account_error_wrong_password), vm.uiState.value.credentialError)
        assertNull(vm.uiState.value.error)
        verify { coordinator.acknowledgeSelf() }
    }

    @Test
    fun `WrongPassword in DELETE mode says the confirmation was not accepted`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.switchMode()

        selfFlow.value = DeletionState.Failed(DeletionError.WrongPassword)
        advanceUntilIdle()

        assertEquals(text(R.string.delete_account_error_wrong_confirmation), vm.uiState.value.credentialError)
    }

    @Test
    fun `RateLimited never produces wrong-password text`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        selfFlow.value = DeletionState.Failed(DeletionError.RateLimited(null))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(text(R.string.delete_account_error_rate_limited), state.error)
        assertNull(state.credentialError)
        assertNotEquals(text(R.string.delete_account_error_wrong_password), state.error)
        assertNotEquals(text(R.string.delete_account_error_wrong_password), state.blockingNotice)
    }

    @Test
    fun `Network keeps the credential and offers a retry`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onCredentialChange("secret")

        selfFlow.value = DeletionState.Failed(DeletionError.Network)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("secret", state.credential)
        assertTrue(state.errorRetryable)
        assertEquals(text(R.string.delete_account_error_network), state.error)
    }

    @Test
    fun `retry after a network error sends the request once without asking again`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onCredentialChange("secret")
        selfFlow.value = DeletionState.Failed(DeletionError.Network)
        advanceUntilIdle()

        vm.retry()

        verify(exactly = 1) { coordinator.deleteOwnAccount(DeleteAccountRequest.forPassword("secret")) }
    }

    @Test
    fun `Succeeded sets deleted`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        selfFlow.value = DeletionState.Succeeded
        advanceUntilIdle()

        assertTrue(vm.uiState.value.deleted)
        verify { coordinator.acknowledgeSelf() }
    }

    @Test
    fun `InFlight blocks input`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onCredentialChange("secret")

        selfFlow.value = DeletionState.InFlight
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isLoading)
        assertFalse(vm.uiState.value.canSubmit)
    }

    @Test
    fun `changing the credential clears the inline error and the blocking notice`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        selfFlow.value = DeletionState.Failed(DeletionError.WrongPassword)
        advanceUntilIdle()

        vm.onCredentialChange("again")

        assertNull(vm.uiState.value.credentialError)
        assertNull(vm.uiState.value.blockingNotice)
    }

    @Test
    fun `toString never contains the credential`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onCredentialChange("hunter2-secret")
        assertFalse(vm.uiState.value.toString().contains("hunter2-secret"))
        coVerify(exactly = 0) { authRepository.deleteAccount(any()) }
    }

    // ---------------------------------------------------------------- US3: child wording inputs

    @Test
    fun `a child never loads linked children and follows the pending reviews`() = runTest {
        every { preferencesManager.isParent } returns false
        every { imageBlurManager.getPendingReviews() } returns listOf(mockk())

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(DeleteRole.CHILD, vm.uiState.value.role)
        assertNull(vm.uiState.value.linkedChildCount)
        assertTrue(vm.uiState.value.hasReviewOriginals)
        coVerify(exactly = 0) { familyRepository.getLinkedChildren() }
    }

    @Test
    fun `a child with no pending reviews reports no originals`() = runTest {
        every { preferencesManager.isParent } returns false
        every { imageBlurManager.getPendingReviews() } returns emptyList()

        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.hasReviewOriginals)
    }

    @Test
    fun `a parent loads the linked child count and never reads the review originals`() = runTest {
        coEvery { familyRepository.getLinkedChildren() } returns NetworkResult.Success(emptyList())

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(DeleteRole.PARENT, vm.uiState.value.role)
        assertEquals(0, vm.uiState.value.linkedChildCount)
        verify(exactly = 0) { imageBlurManager.getPendingReviews() }
    }

    // ---------------------------------------------------------------- US4: supervised child

    @Test
    fun `SupervisedChild sets the friendly message and never mentions a wrong password`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        selfFlow.value = DeletionState.Failed(DeletionError.SupervisedChild)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(text(R.string.delete_account_error_supervised), state.error)
        assertEquals(text(R.string.delete_account_error_supervised), state.blockingNotice)
        assertNull(state.credentialError)
        assertFalse(state.errorRetryable)
        assertFalse(state.deleted)
    }

    @Test
    fun `a linked child can still submit because the server decides, not the local role`() = runTest {
        every { preferencesManager.isParent } returns false
        every { preferencesManager.hasLinkedParent } returns true
        val vm = viewModel()
        advanceUntilIdle()
        vm.onCredentialChange("secret")

        assertTrue(vm.uiState.value.canSubmit)
        vm.onDeleteClicked()
        assertTrue(vm.uiState.value.showFinalConfirm)
        vm.onFinalConfirm()

        verify(exactly = 1) { coordinator.deleteOwnAccount(DeleteAccountRequest.forPassword("secret")) }
    }

    @Test
    fun `the supervised notice stays until the credential changes`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        selfFlow.value = DeletionState.Failed(DeletionError.SupervisedChild)
        advanceUntilIdle()
        vm.clearError()

        assertEquals(text(R.string.delete_account_error_supervised), vm.uiState.value.blockingNotice)

        vm.onCredentialChange("x")
        assertNull(vm.uiState.value.blockingNotice)
    }
}
