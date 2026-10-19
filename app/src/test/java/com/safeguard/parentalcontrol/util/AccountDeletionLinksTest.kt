package com.safeguard.parentalcontrol.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountDeletionLinksTest {

    @Test
    fun `strips the api prefix and a trailing slash`() {
        assertEquals(
            "https://bw.noor.net:8090/account-deletion",
            AccountDeletionLinks.publicDeletionPageUrl("https://bw.noor.net:8090/api/v1/")
        )
    }

    @Test
    fun `strips the api prefix without a trailing slash`() {
        assertEquals(
            "https://bw.noor.net:8090/account-deletion",
            AccountDeletionLinks.publicDeletionPageUrl("https://bw.noor.net:8090/api/v1")
        )
    }

    @Test
    fun `keeps a path in front of the api prefix`() {
        assertEquals(
            "https://h/x/account-deletion",
            AccountDeletionLinks.publicDeletionPageUrl("https://h/x/api/v1/")
        )
    }

    @Test
    fun `a base with no api prefix is used as the root`() {
        assertEquals(
            "https://h/account-deletion",
            AccountDeletionLinks.publicDeletionPageUrl("https://h/")
        )
    }
}
