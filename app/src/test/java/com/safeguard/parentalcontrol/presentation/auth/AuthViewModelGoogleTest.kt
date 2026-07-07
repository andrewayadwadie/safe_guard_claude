package com.safeguard.parentalcontrol.presentation.auth

import android.content.Context
import com.safeguard.parentalcontrol.MainDispatcherRule
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.User
import com.safeguard.parentalcontrol.data.model.UserRole
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AuthRepository
import com.safeguard.parentalcontrol.data.repository.DeviceRepository
import com.safeguard.parentalcontrol.util.AnalyticsHelper
import com.safeguard.parentalcontrol.util.GoogleSignInManager
import com.safeguard.parentalcontrol.util.GoogleSignInResult
import com.safeguard.parentalcontrol.util.LocaleHelper
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelGoogleTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var authRepository: AuthRepository
    private lateinit var deviceRepository: DeviceRepository
    private lateinit var googleSignInManager: GoogleSignInManager
    private lateinit var analyticsHelper: AnalyticsHelper

    private val idToken = "fake-id-token"
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setup() {
        authRepository = mockk(relaxed = true)
        deviceRepository = mockk(relaxed = true)
        googleSignInManager = mockk(relaxed = true)
        analyticsHelper = mockk(relaxed = true)

        // ViewModel init() reads these
        every { authRepository.isLoggedIn() } returns false
        every { deviceRepository.isDeviceRegistered() } returns false

        // AuthViewModel.getString() calls LocaleHelper.localizedContext(context).getString(...);
        // the real implementation builds a Configuration off android.jar, which isn't available
        // under plain JUnit (no Robolectric here). Skip the wrapping and resolve directly against
        // this mock so getString(...) stubs below take effect.
        mockkObject(LocaleHelper)
        every { LocaleHelper.localizedContext(any()) } returns context
        every {
            context.getString(R.string.auth_error_google_signin_failed, *anyVararg())
        } returns "Sign-in failed. Please try again."
    }

    @After
    fun tearDown() {
        unmockkObject(LocaleHelper)
    }

    private fun viewModel() = AuthViewModel(
        authRepository, deviceRepository, googleSignInManager, analyticsHelper, context
    )

    @Test
    fun `cancelGoogleRoleSelection clears all google fields including loading`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        // Drive into needsRoleSelection state first
        coEvery { googleSignInManager.signIn(context) } returns
            GoogleSignInResult.Success(idToken, "e@x.com", null)
        coEvery { authRepository.googleSignIn(idToken, null) } returns
            NetworkResult.Error("role_required", 422)

        vm.signInWithGoogle(context)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.needsRoleSelection)
        assertEquals(idToken, vm.uiState.value.pendingGoogleIdToken)

        vm.cancelGoogleRoleSelection()

        val state = vm.uiState.value
        assertFalse(state.needsRoleSelection)
        assertNull(state.pendingGoogleIdToken)
        assertFalse(state.isGoogleSignInLoading)
    }

    @Test
    fun `role required error with snake_case sets needsRoleSelection and stores token`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        coEvery { googleSignInManager.signIn(context) } returns
            GoogleSignInResult.Success(idToken, "e@x.com", null)
        coEvery { authRepository.googleSignIn(idToken, null) } returns
            NetworkResult.Error("role_required", 422)

        vm.signInWithGoogle(context)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.needsRoleSelection)
        assertEquals(idToken, state.pendingGoogleIdToken)
        assertFalse(state.isGoogleSignInLoading)
        assertNull(state.error)
    }

    @Test
    fun `role required error with human phrase also detected`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        coEvery { googleSignInManager.signIn(context) } returns
            GoogleSignInResult.Success(idToken, "e@x.com", null)
        coEvery { authRepository.googleSignIn(idToken, null) } returns
            NetworkResult.Error("Role is required", 400)

        vm.signInWithGoogle(context)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.needsRoleSelection)
    }

    @Test
    fun `generic backend error maps to generic message no raw detail`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        coEvery { googleSignInManager.signIn(context) } returns
            GoogleSignInResult.Success(idToken, "e@x.com", null)
        coEvery { authRepository.googleSignIn(idToken, null) } returns
            NetworkResult.Error("Internal server error 500 stacktrace", 500)

        vm.signInWithGoogle(context)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("Sign-in failed. Please try again.", state.error)
        assertFalse(state.needsRoleSelection)
        assertFalse(state.isGoogleSignInLoading)
    }

    @Test
    fun `cancelled picker emits failed cancelled analytics and no error`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        coEvery { googleSignInManager.signIn(context) } returns GoogleSignInResult.Cancelled

        vm.signInWithGoogle(context)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isGoogleSignInLoading)
        assertNull(state.error)
        verify { analyticsHelper.logGoogleSignInTapped() }
        verify { analyticsHelper.logGoogleSignInFailed("cancelled") }
    }

    @Test
    fun `successful existing user sign in emits success analytics`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        val user = mockk<User>(relaxed = true)
        coEvery { googleSignInManager.signIn(context) } returns
            GoogleSignInResult.Success(idToken, "e@x.com", null)
        coEvery { authRepository.googleSignIn(idToken, null) } returns
            NetworkResult.Success(user)

        vm.signInWithGoogle(context)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isLoggedIn)
        verify { analyticsHelper.logGoogleSignInSuccess() }
    }

    @Test
    fun `completeGoogleRegistration emits role selected analytics`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        // Get into role-selection state
        coEvery { googleSignInManager.signIn(context) } returns
            GoogleSignInResult.Success(idToken, "e@x.com", null)
        coEvery { authRepository.googleSignIn(idToken, null) } returns
            NetworkResult.Error("role_required", 422)
        vm.signInWithGoogle(context)
        advanceUntilIdle()

        val user = mockk<User>(relaxed = true)
        coEvery { authRepository.googleSignIn(idToken, UserRole.PARENT) } returns
            NetworkResult.Success(user)

        vm.completeGoogleRegistration(UserRole.PARENT)
        advanceUntilIdle()

        verify { analyticsHelper.logGoogleRoleSelected(UserRole.PARENT) }
        coVerify { authRepository.googleSignIn(idToken, UserRole.PARENT) }
    }
}
