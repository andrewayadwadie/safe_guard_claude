package com.safeguard.parentalcontrol.presentation.forgotpassword

import android.content.Context
import com.safeguard.parentalcontrol.MainDispatcherRule
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.MessageResponse
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AuthRepository
import com.safeguard.parentalcontrol.util.LocaleHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
class ForgotPasswordViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var authRepository: AuthRepository
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setup() {
        authRepository = mockk(relaxed = true)

        // getString() resolves through LocaleHelper.localizedContext(context); short-circuit
        // it to the mock context so the R.string stubs below apply (no Robolectric here).
        mockkObject(LocaleHelper)
        every { LocaleHelper.localizedContext(any()) } returns context
        every { context.getString(R.string.forgotpw_error_email_invalid, *anyVararg()) } returns "invalid email"
        every { context.getString(R.string.forgotpw_error_code_incomplete, *anyVararg()) } returns "incomplete code"
        every { context.getString(R.string.forgotpw_error_password_too_short, *anyVararg()) } returns "too short"
        every { context.getString(R.string.forgotpw_error_password_mismatch, *anyVararg()) } returns "mismatch"
        every { context.getString(R.string.forgotpw_error_code_invalid, *anyVararg()) } returns "invalid code"
        every { context.getString(R.string.forgotpw_error_rate_limited, *anyVararg()) } returns "rate limited"
    }

    @After
    fun tearDown() {
        unmockkObject(LocaleHelper)
    }

    private fun viewModel() = ForgotPasswordViewModel(authRepository, context)

    // ---- submitEmail ----

    @Test
    fun `invalid email sets field error and never calls repository`() = runTest {
        val vm = viewModel()
        vm.onEmailChange("not-an-email")

        vm.submitEmail()
        advanceUntilIdle()

        assertEquals("invalid email", vm.uiState.value.emailFieldError)
        assertFalse(vm.uiState.value.codeSent)
        coVerify(exactly = 0) { authRepository.forgotPassword(any()) }
    }

    @Test
    fun `valid email success advances to reset step and starts cooldown`() = runTest {
        coEvery { authRepository.forgotPassword("user@example.com") } returns
            NetworkResult.Success(MessageResponse("sent", true))
        val vm = viewModel()
        vm.onEmailChange("user@example.com")

        vm.submitEmail()
        // runCurrent (not advanceUntilIdle) so the cooldown timer isn't drained to 0.
        runCurrent()

        val state = vm.uiState.value
        assertEquals(ForgotPasswordStep.RESET, state.step)
        assertTrue(state.codeSent)
        assertEquals(60, state.cooldownSeconds)
        assertNull(state.emailFieldError)
        assertFalse(state.isLoading)
    }

    @Test
    fun `rate limited send maps to rate limit message`() = runTest {
        coEvery { authRepository.forgotPassword(any()) } returns
            NetworkResult.Error("Rate limit exceeded", 429)
        val vm = viewModel()
        vm.onEmailChange("user@example.com")

        vm.submitEmail()
        advanceUntilIdle()

        assertEquals("rate limited", vm.uiState.value.error)
        assertEquals(ForgotPasswordStep.EMAIL, vm.uiState.value.step)
    }

    // ---- submitReset ----

    @Test
    fun `short code sets code field error without network`() = runTest {
        val vm = viewModel()

        vm.submitReset(code = "123", newPassword = "password123", confirmPassword = "password123")
        advanceUntilIdle()

        assertEquals("incomplete code", vm.uiState.value.codeFieldError)
        coVerify(exactly = 0) { authRepository.resetPassword(any(), any(), any()) }
    }

    @Test
    fun `short password sets password field error without network`() = runTest {
        val vm = viewModel()

        vm.submitReset(code = "123456", newPassword = "short", confirmPassword = "short")
        advanceUntilIdle()

        assertEquals("too short", vm.uiState.value.passwordFieldError)
        coVerify(exactly = 0) { authRepository.resetPassword(any(), any(), any()) }
    }

    @Test
    fun `password mismatch sets password field error without network`() = runTest {
        val vm = viewModel()

        vm.submitReset(code = "123456", newPassword = "password123", confirmPassword = "different1")
        advanceUntilIdle()

        assertEquals("mismatch", vm.uiState.value.passwordFieldError)
        coVerify(exactly = 0) { authRepository.resetPassword(any(), any(), any()) }
    }

    @Test
    fun `successful reset sets resetSuccess`() = runTest {
        coEvery { authRepository.resetPassword(any(), any(), any()) } returns
            NetworkResult.Success(MessageResponse("done", true))
        val vm = viewModel()

        vm.submitReset(code = "123456", newPassword = "password123", confirmPassword = "password123")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.resetSuccess)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `reset 400 sets code field error`() = runTest {
        coEvery { authRepository.resetPassword(any(), any(), any()) } returns
            NetworkResult.Error("Invalid or expired reset code", 400)
        val vm = viewModel()

        vm.submitReset(code = "123456", newPassword = "password123", confirmPassword = "password123")
        advanceUntilIdle()

        assertEquals("invalid code", vm.uiState.value.codeFieldError)
        assertFalse(vm.uiState.value.resetSuccess)
    }

    @Test
    fun `reset 422 sets password field error from server message`() = runTest {
        coEvery { authRepository.resetPassword(any(), any(), any()) } returns
            NetworkResult.Error("String should have at least 8 characters", 422)
        val vm = viewModel()

        vm.submitReset(code = "123456", newPassword = "password123", confirmPassword = "password123")
        advanceUntilIdle()

        assertEquals("String should have at least 8 characters", vm.uiState.value.passwordFieldError)
    }

    // ---- resend cooldown ----

    @Test
    fun `cooldown decrements over time and blocks resend while active`() = runTest {
        coEvery { authRepository.forgotPassword(any()) } returns
            NetworkResult.Success(MessageResponse("sent", true))
        val vm = viewModel()
        vm.onEmailChange("user@example.com")

        vm.submitEmail()
        runCurrent()
        assertEquals(60, vm.uiState.value.cooldownSeconds)

        // Resend is a no-op while cooldown > 0.
        vm.resendCode()
        runCurrent()
        // forgotPassword called exactly once (initial send only).
        coVerify(exactly = 1) { authRepository.forgotPassword(any()) }

        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(vm.uiState.value.cooldownSeconds <= 57)
    }

    @Test
    fun `resend after cooldown restarts the countdown`() = runTest {
        coEvery { authRepository.forgotPassword(any()) } returns
            NetworkResult.Success(MessageResponse("sent", true))
        val vm = viewModel()
        vm.onEmailChange("user@example.com")

        vm.submitEmail()
        runCurrent()

        // Fast-forward past the full cooldown.
        advanceTimeBy(61_000)
        runCurrent()
        assertEquals(0, vm.uiState.value.cooldownSeconds)

        vm.resendCode()
        runCurrent()

        assertEquals(60, vm.uiState.value.cooldownSeconds)
        coVerify(exactly = 2) { authRepository.forgotPassword(any()) }
    }

    @Test
    fun `backToEmailStep preserves email and resets step`() = runTest {
        coEvery { authRepository.forgotPassword(any()) } returns
            NetworkResult.Success(MessageResponse("sent", true))
        val vm = viewModel()
        vm.onEmailChange("user@example.com")
        vm.submitEmail()
        advanceUntilIdle()

        vm.backToEmailStep()

        assertEquals(ForgotPasswordStep.EMAIL, vm.uiState.value.step)
        assertEquals("user@example.com", vm.uiState.value.email)
    }
}
