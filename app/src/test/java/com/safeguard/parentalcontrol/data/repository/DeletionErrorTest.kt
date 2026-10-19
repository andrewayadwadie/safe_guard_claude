package com.safeguard.parentalcontrol.data.repository

import com.safeguard.parentalcontrol.data.remote.NetworkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionErrorTest {

    private fun err(code: Int?) = NetworkResult.Error("ignored message: wrong password", code)

    @Test
    fun `self delete maps each status code`() {
        assertEquals(DeletionError.WrongPassword, DeletionError.fromSelfDelete(err(400)))
        assertEquals(DeletionError.SessionOver, DeletionError.fromSelfDelete(err(401)))
        assertEquals(DeletionError.SupervisedChild, DeletionError.fromSelfDelete(err(403)))
        assertEquals(DeletionError.RateLimited(null), DeletionError.fromSelfDelete(err(429)))
        assertEquals(DeletionError.Network, DeletionError.fromSelfDelete(err(null)))
        assertEquals(DeletionError.Unknown(500), DeletionError.fromSelfDelete(err(500)))
    }

    @Test
    fun `self delete never treats 404 as a child-not-found`() {
        assertEquals(DeletionError.Unknown(404), DeletionError.fromSelfDelete(err(404)))
    }

    @Test
    fun `child delete maps each status code and never produces wrong password`() {
        assertEquals(DeletionError.ChildNotFound, DeletionError.fromChildDelete(err(404)))
        assertEquals(DeletionError.SessionOver, DeletionError.fromChildDelete(err(401)))
        assertEquals(DeletionError.RateLimited(null), DeletionError.fromChildDelete(err(429)))
        assertEquals(DeletionError.Network, DeletionError.fromChildDelete(err(null)))
        assertEquals(DeletionError.Unknown(400), DeletionError.fromChildDelete(err(400)))
        assertEquals(DeletionError.Unknown(403), DeletionError.fromChildDelete(err(403)))
    }

    @Test
    fun `reauth maps 400 and 401 to wrong password`() {
        assertEquals(DeletionError.WrongPassword, DeletionError.fromReauth(err(400)))
        assertEquals(DeletionError.WrongPassword, DeletionError.fromReauth(err(401)))
    }

    @Test
    fun `reauth account mismatch sentinel maps to AccountMismatch and never to wrong password or session over`() {
        val mapped = DeletionError.fromReauth(err(DeletionError.CODE_ACCOUNT_MISMATCH))
        assertEquals(DeletionError.AccountMismatch, mapped)
        assertNotEquals(DeletionError.WrongPassword, mapped)
        assertNotEquals(DeletionError.SessionOver, mapped)
    }

    @Test
    fun `reauth maps rate limit, network and unknown`() {
        assertEquals(DeletionError.RateLimited(null), DeletionError.fromReauth(err(429)))
        assertEquals(DeletionError.Network, DeletionError.fromReauth(err(null)))
        assertEquals(DeletionError.Unknown(500), DeletionError.fromReauth(err(500)))
    }

    @Test
    fun `sentinel cannot collide with an http status`() {
        assertTrue(DeletionError.CODE_ACCOUNT_MISMATCH < 0)
    }

    @Test
    fun `only 400 on the self delete path yields wrong password`() {
        val others = listOf(null, 401, 403, 404, 429, 500, 503, DeletionError.CODE_ACCOUNT_MISMATCH)
        others.forEach { code ->
            assertNotEquals(
                "code=$code must not be WrongPassword",
                DeletionError.WrongPassword,
                DeletionError.fromSelfDelete(err(code))
            )
        }
    }

    @Test
    fun `mapping ignores the message text`() {
        val mapped = DeletionError.fromSelfDelete(NetworkResult.Error("Incorrect password", 429))
        assertEquals(DeletionError.RateLimited(null), mapped)
    }
}
