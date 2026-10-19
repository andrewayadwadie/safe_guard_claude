package com.safeguard.parentalcontrol.data.repository

import com.google.gson.GsonBuilder
import com.safeguard.parentalcontrol.data.model.DeleteAccountRequest
import com.safeguard.parentalcontrol.data.model.LoginRequest
import com.safeguard.parentalcontrol.data.model.TokenResponse
import com.safeguard.parentalcontrol.data.model.User
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.util.FlexibleDateAdapter
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.Date

class AuthRepositoryDeleteAccountTest {

    private lateinit var apiService: ApiService
    private lateinit var tokenManager: TokenManager
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var repository: AuthRepository

    private val request = DeleteAccountRequest.forPassword("secret")

    @Before
    fun setup() {
        apiService = mockk()
        tokenManager = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        repository = AuthRepository(
            apiService,
            tokenManager,
            preferencesManager,
            mockk<AlertRepository>(relaxed = true)
        )
    }

    private fun rawResponse(code: Int) = okhttp3.Response.Builder()
        .code(code)
        .message("msg")
        .protocol(Protocol.HTTP_1_1)
        .request(Request.Builder().url("http://localhost/").build())
        .build()

    @Test
    fun `200 with a body is a success`() = runTest {
        coEvery { apiService.deleteAccount(any()) } returns Response.success(Unit)
        assertEquals(NetworkResult.Success(Unit), repository.deleteAccount(request))
    }

    @Test
    fun `empty 200 is a success`() = runTest {
        coEvery { apiService.deleteAccount(any()) } returns
            Response.success<Unit>(null, rawResponse(200))
        assertEquals(NetworkResult.Success(Unit), repository.deleteAccount(request))
    }

    @Test
    fun `204 with a null body is a success`() = runTest {
        coEvery { apiService.deleteAccount(any()) } returns
            Response.success<Unit>(null, rawResponse(204))
        assertEquals(NetworkResult.Success(Unit), repository.deleteAccount(request))
    }

    @Test
    fun `400 keeps its code`() = runTest {
        coEvery { apiService.deleteAccount(any()) } returns Response.error(
            400,
            """{"detail":"Incorrect password"}""".toResponseBody("application/json".toMediaType())
        )
        val result = repository.deleteAccount(request)
        assertTrue(result is NetworkResult.Error)
        assertEquals(400, (result as NetworkResult.Error).code)
    }

    @Test
    fun `io exception has no code`() = runTest {
        coEvery { apiService.deleteAccount(any()) } throws IOException("offline")
        val result = repository.deleteAccount(request)
        assertTrue(result is NetworkResult.Error)
        assertEquals(null, (result as NetworkResult.Error).code)
    }

    private val gson = GsonBuilder()
        .registerTypeAdapter(Date::class.java, FlexibleDateAdapter())
        .create()

    @Test
    fun `password request serializes to exactly the password field`() {
        assertEquals("""{"password":"x"}""", gson.toJson(DeleteAccountRequest.forPassword("x")))
    }

    @Test
    fun `google request serializes to exactly the confirmation field`() {
        assertEquals("""{"confirmation":"DELETE"}""", gson.toJson(DeleteAccountRequest.forGoogle()))
    }

    // ---------------------------------------------------------------- re-authentication

    private fun tokenResponse(userId: Int) = TokenResponse(
        accessToken = "access",
        refreshToken = "refresh",
        tokenType = "bearer",
        user = mockk<User>(relaxed = true).also { every { it.id } returns userId }
    )

    @Test
    fun `password re-auth sends the stored email and saves the tokens when the id matches`() = runTest {
        every { preferencesManager.userEmail } returns "parent@example.com"
        every { preferencesManager.userId } returns 5
        val sent = slot<LoginRequest>()
        coEvery { apiService.login(capture(sent)) } returns Response.success(tokenResponse(5))

        val result = repository.reauthenticateWithPassword("parent-secret")

        assertEquals(NetworkResult.Success(Unit), result)
        assertEquals(LoginRequest("parent@example.com", "parent-secret"), sent.captured)
        verify(exactly = 1) { tokenManager.saveTokens("access", "refresh") }
    }

    @Test
    fun `password re-auth with a different user id is an account mismatch and saves nothing`() = runTest {
        every { preferencesManager.userEmail } returns "parent@example.com"
        every { preferencesManager.userId } returns 5
        coEvery { apiService.login(any()) } returns Response.success(tokenResponse(99))

        val result = repository.reauthenticateWithPassword("parent-secret")

        assertEquals(DeletionError.CODE_ACCOUNT_MISMATCH, (result as NetworkResult.Error).code)
        verify(exactly = 0) { tokenManager.saveTokens(any(), any()) }
        verify(exactly = 0) { preferencesManager.saveUserInfo(any(), any(), any(), any()) }
    }

    @Test
    fun `password re-auth with no stored email is an account mismatch without a request`() = runTest {
        every { preferencesManager.userEmail } returns null

        val result = repository.reauthenticateWithPassword("parent-secret")

        assertEquals(DeletionError.CODE_ACCOUNT_MISMATCH, (result as NetworkResult.Error).code)
        coVerify(exactly = 0) { apiService.login(any()) }
    }

    @Test
    fun `password re-auth keeps the server status for a wrong password`() = runTest {
        every { preferencesManager.userEmail } returns "parent@example.com"
        coEvery { apiService.login(any()) } returns Response.error(
            401,
            """{"detail":"Incorrect email or password"}""".toResponseBody("application/json".toMediaType())
        )

        val result = repository.reauthenticateWithPassword("wrong")

        assertEquals(401, (result as NetworkResult.Error).code)
        verify(exactly = 0) { tokenManager.saveTokens(any(), any()) }
    }

    @Test
    fun `google re-auth saves the tokens when the id matches and rejects a different account`() = runTest {
        every { preferencesManager.userId } returns 5
        coEvery { apiService.googleAuth(any()) } returns Response.success(tokenResponse(5))
        assertEquals(NetworkResult.Success(Unit), repository.reauthenticateWithGoogle("id-token"))
        verify(exactly = 1) { tokenManager.saveTokens("access", "refresh") }

        coEvery { apiService.googleAuth(any()) } returns Response.success(tokenResponse(77))
        val mismatch = repository.reauthenticateWithGoogle("id-token")
        assertEquals(DeletionError.CODE_ACCOUNT_MISMATCH, (mismatch as NetworkResult.Error).code)
        verify(exactly = 1) { tokenManager.saveTokens(any(), any()) }
    }

    @Test
    fun `family repository maps a 204 child delete to success`() = runTest {
        coEvery { apiService.deleteChildAccount(7) } returns Response.success<Unit>(null, rawResponse(204))
        val family = FamilyRepository(apiService, preferencesManager)

        assertEquals(NetworkResult.Success(Unit), family.deleteChildAccount(7))
    }

    @Test
    fun `family repository keeps the code of a 404`() = runTest {
        coEvery { apiService.deleteChildAccount(7) } returns Response.error(
            404,
            """{"detail":"not linked"}""".toResponseBody("application/json".toMediaType())
        )
        val family = FamilyRepository(apiService, preferencesManager)

        assertEquals(404, (family.deleteChildAccount(7) as NetworkResult.Error).code)
    }

    @Test
    fun `request toString never contains the password`() {
        val text = DeleteAccountRequest.forPassword("hunter2-secret").toString()
        assertTrue(!text.contains("hunter2-secret"))
    }
}
