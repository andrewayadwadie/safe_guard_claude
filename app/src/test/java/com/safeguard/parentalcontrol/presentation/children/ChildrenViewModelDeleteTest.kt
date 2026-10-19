package com.safeguard.parentalcontrol.presentation.children

import android.content.Context
import com.safeguard.parentalcontrol.MainDispatcherRule
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.account.AccountDeletionCoordinator
import com.safeguard.parentalcontrol.data.account.ChildReauth
import com.safeguard.parentalcontrol.data.account.DeletionState
import com.safeguard.parentalcontrol.data.model.AuthProvider
import com.safeguard.parentalcontrol.data.model.FamilyLink
import com.safeguard.parentalcontrol.data.model.User
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AuthRepository
import com.safeguard.parentalcontrol.data.repository.DeletionError
import com.safeguard.parentalcontrol.data.repository.FamilyRepository
import com.safeguard.parentalcontrol.util.GoogleSignInManager
import com.safeguard.parentalcontrol.util.GoogleSignInResult
import com.safeguard.parentalcontrol.util.LocaleHelper
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class ChildrenViewModelDeleteTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var familyRepository: FamilyRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var coordinator: AccountDeletionCoordinator
    private lateinit var googleSignInManager: GoogleSignInManager
    private val context = mockk<Context>(relaxed = true)
    private val childStates = MutableStateFlow<Map<Int, DeletionState>>(emptyMap())

    // link id 100 and child id 7 are deliberately different: the wrong one returns 404 on the server.
    private val sara = FamilyLink(100, 1, 7, "sara@example.com", "Sara", Date())
    private val omar = FamilyLink(101, 1, 8, "omar@example.com", "Omar", Date())

    private fun text(resId: Int, vararg args: Any) = "res:$resId:${args.joinToString(",")}"

    @Before
    fun setup() {
        familyRepository = mockk()
        authRepository = mockk()
        coordinator = mockk(relaxed = true)
        googleSignInManager = mockk()

        coEvery { familyRepository.getLinkedChildren() } returns NetworkResult.Success(listOf(sara, omar))
        coEvery { authRepository.getCurrentUser() } returns NetworkResult.Error("offline", null)
        every { coordinator.childStates } returns childStates
        every { coordinator.acknowledgeChild(any()) } answers {
            val id = firstArg<Int>()
            childStates.update { it - id }
        }

        mockkObject(LocaleHelper)
        every { LocaleHelper.localizedContext(any()) } returns context
        every { context.getString(any(), *anyVararg()) } answers {
            val formatArgs = args.drop(1).flatMap { (it as? Array<*>)?.toList() ?: listOf(it) }
            text(firstArg(), *formatArgs.map { it.toString() }.toTypedArray())
        }
    }

    @After
    fun tearDown() {
        unmockkObject(LocaleHelper)
    }

    private fun viewModel() = ChildrenViewModel(
        familyRepository, authRepository, coordinator, googleSignInManager, context
    )

    private fun ChildrenViewModel.openFor(link: FamilyLink, name: String = link.childName, password: String = "pw") {
        openDeleteDialog(link)
        onDeleteNameChange(name)
        onDeleteCredentialChange(password)
    }

    @Test
    fun `canDelete needs the exact name and a password`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        vm.openDeleteDialog(sara)
        assertFalse(vm.uiState.value.canDelete)

        vm.onDeleteNameChange("sara")
        vm.onDeleteCredentialChange("pw")
        assertFalse("case must match", vm.uiState.value.canDelete)

        vm.onDeleteNameChange("Sara ")
        assertFalse("no trimming", vm.uiState.value.canDelete)

        vm.onDeleteNameChange("Sara")
        assertTrue(vm.uiState.value.canDelete)

        vm.onDeleteCredentialChange("")
        assertFalse(vm.uiState.value.canDelete)
    }

    @Test
    fun `an Arabic name is compared as typed`() = runTest {
        val arabic = FamilyLink(100, 1, 7, "s@example.com", "سارة", Date())
        val vm = viewModel()
        advanceUntilIdle()
        vm.openDeleteDialog(arabic)
        vm.onDeleteCredentialChange("pw")

        vm.onDeleteNameChange("سارة")
        assertTrue(vm.uiState.value.canDelete)
        vm.onDeleteNameChange("ساره")
        assertFalse(vm.uiState.value.canDelete)
    }

    @Test
    fun `a Google parent needs no password`() = runTest {
        val user = mockk<User>(relaxed = true)
        every { user.authProvider } returns AuthProvider.GOOGLE
        coEvery { authRepository.getCurrentUser() } returns NetworkResult.Success(user)
        val vm = viewModel()
        advanceUntilIdle()

        vm.openDeleteDialog(sara)
        vm.onDeleteNameChange("Sara")

        assertTrue(vm.uiState.value.parentIsGoogle)
        assertTrue(vm.uiState.value.canDelete)
    }

    @Test
    fun `confirm sends the child id, not the link id, with the typed password`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openFor(sara, password = "parent-pw")

        vm.confirmDeletePassword()

        verify(exactly = 1) { coordinator.deleteChildAccount(7, ChildReauth.Password("parent-pw")) }
        verify(exactly = 0) { coordinator.deleteChildAccount(100, any()) }
    }

    @Test
    fun `confirm does nothing while the name does not match`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openFor(sara, name = "Sar")

        vm.confirmDeletePassword()

        verify(exactly = 0) { coordinator.deleteChildAccount(any(), any()) }
    }

    @Test
    fun `success removes the child by child id and closes the dialog`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openFor(sara)

        childStates.value = mapOf(7 to DeletionState.Succeeded)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf(omar), state.children)
        assertNull(state.deleteTarget)
        assertEquals("", state.deleteCredential)
        assertEquals(text(R.string.children_delete_success, "Sara"), state.successMessage)
        assertEquals(1, state.childrenChangedCount)
        verify { coordinator.acknowledgeChild(7) }
    }

    @Test
    fun `deleting one child leaves another child's open dialog alone`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openFor(omar)

        childStates.value = mapOf(7 to DeletionState.Succeeded)
        advanceUntilIdle()

        assertEquals(omar, vm.uiState.value.deleteTarget)
        assertEquals(listOf(omar), vm.uiState.value.children)
    }

    @Test
    fun `child not found closes the dialog, reloads and says it is no longer linked`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openFor(sara)

        childStates.value = mapOf(7 to DeletionState.Failed(DeletionError.ChildNotFound))
        advanceUntilIdle()

        assertNull(vm.uiState.value.deleteTarget)
        assertEquals(text(R.string.children_delete_not_linked, "Sara"), vm.uiState.value.successMessage)
        coVerify(atLeast = 2) { familyRepository.getLinkedChildren() }
    }

    @Test
    fun `wrong password stays in the dialog as an inline error`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openFor(sara)

        childStates.value = mapOf(7 to DeletionState.Failed(DeletionError.WrongPassword))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull(state.deleteTarget)
        assertEquals(text(R.string.delete_account_error_wrong_password), state.deleteCredentialError)
        assertNull(state.error)
        assertNull(state.deletingChildId)
    }

    @Test
    fun `account mismatch closes the dialog and names the right message per parent type`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openFor(sara)

        childStates.value = mapOf(7 to DeletionState.Failed(DeletionError.AccountMismatch))
        advanceUntilIdle()

        assertNull(vm.uiState.value.deleteTarget)
        assertEquals(text(R.string.children_delete_account_mismatch), vm.uiState.value.error)
        assertEquals(listOf(sara, omar), vm.uiState.value.children)
    }

    @Test
    fun `rate limit and network errors stay inside the dialog`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openFor(sara)

        childStates.value = mapOf(7 to DeletionState.Failed(DeletionError.RateLimited(null)))
        advanceUntilIdle()
        assertEquals(text(R.string.delete_account_error_rate_limited), vm.uiState.value.deleteDialogNotice)
        assertNull(vm.uiState.value.error)
        assertNotNull(vm.uiState.value.deleteTarget)

        childStates.value = mapOf(7 to DeletionState.Failed(DeletionError.Network))
        advanceUntilIdle()
        assertEquals(text(R.string.delete_account_error_network), vm.uiState.value.deleteDialogNotice)
    }

    @Test
    fun `session over closes the dialog without a message`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openFor(sara)

        childStates.value = mapOf(7 to DeletionState.Failed(DeletionError.SessionOver))
        advanceUntilIdle()

        assertNull(vm.uiState.value.deleteTarget)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `an in-flight deletion disables the dialog and cannot be dismissed`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openFor(sara)

        childStates.value = mapOf(7 to DeletionState.InFlight)
        advanceUntilIdle()
        assertEquals(7, vm.uiState.value.deletingChildId)
        assertFalse(vm.uiState.value.canDelete)

        vm.closeDeleteDialog()
        assertNotNull(vm.uiState.value.deleteTarget)
    }

    @Test
    fun `google sign-in cancelled keeps the dialog open and sends nothing`() = runTest {
        val user = mockk<User>(relaxed = true)
        every { user.authProvider } returns AuthProvider.GOOGLE
        coEvery { authRepository.getCurrentUser() } returns NetworkResult.Success(user)
        coEvery { googleSignInManager.signIn(any(), any()) } returns GoogleSignInResult.Cancelled
        val vm = viewModel()
        advanceUntilIdle()
        vm.openDeleteDialog(sara)
        vm.onDeleteNameChange("Sara")

        vm.confirmDeleteWithGoogle(context)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.deleteTarget)
        verify(exactly = 0) { coordinator.deleteChildAccount(any(), any()) }
    }

    @Test
    fun `google sign-in success sends the id token`() = runTest {
        val user = mockk<User>(relaxed = true)
        every { user.authProvider } returns AuthProvider.GOOGLE
        coEvery { authRepository.getCurrentUser() } returns NetworkResult.Success(user)
        coEvery { googleSignInManager.signIn(any(), any()) } returns
            GoogleSignInResult.Success("token-abc", "p@example.com", "Parent")
        val vm = viewModel()
        advanceUntilIdle()
        vm.openDeleteDialog(sara)
        vm.onDeleteNameChange("Sara")

        vm.confirmDeleteWithGoogle(context)
        advanceUntilIdle()

        verify(exactly = 1) { coordinator.deleteChildAccount(7, ChildReauth.Google("token-abc")) }
    }

    @Test
    fun `toString never contains the parent password`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openFor(sara, password = "hunter2-secret")
        assertFalse(vm.uiState.value.toString().contains("hunter2-secret"))
    }
}
