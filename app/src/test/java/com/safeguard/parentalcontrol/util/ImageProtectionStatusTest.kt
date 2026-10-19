package com.safeguard.parentalcontrol.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the parent is told about image protection.
 *
 * Feature `016-remove-all-files-access`, data-model entity 4. Two behaviours here are easy to get
 * backwards and both matter: a partial photo grant is **not** success, and the default-off Maximum
 * Protection setting is **not** a problem to nag about.
 */
class ImageProtectionStatusTest {

    private fun resolve(
        read: MediaReadAccess,
        maximumProtectionOn: Boolean = false,
        hasAnyFolderGrant: Boolean = false,
        anyGrantRevoked: Boolean = false,
        missing: List<String> = listOf("CAMERA", "PICTURES")
    ) = ImageProtectionStatusResolver.resolve(
        read = read,
        maximumProtectionOn = maximumProtectionOn,
        hasAnyFolderGrant = hasAnyFolderGrant,
        anyGrantRevoked = anyGrantRevoked,
        missingFolderTargets = missing
    )

    // -----------------------------------------------------------------------------------
    // Reading — the baseline promise
    // -----------------------------------------------------------------------------------

    @Test
    fun `full read access means detection works`() {
        val status = resolve(MediaReadAccess.FULL)
        assertTrue(status.detectionWorks)
    }

    @Test
    fun `partial read access does not count as working`() {
        // A hand-picked selection cannot contain a photo the child receives tomorrow, which is the
        // entire job. Accepting it would leave the parent believing they were protected.
        val status = resolve(MediaReadAccess.PARTIAL, maximumProtectionOn = true)
        assertFalse(status.detectionWorks)
        assertEquals(BlurEnforcement.READ_BLOCKED, status.blur)
        assertTrue(status.needsAttention)
    }

    @Test
    fun `denied read access does not count as working`() {
        val status = resolve(MediaReadAccess.DENIED)
        assertFalse(status.detectionWorks)
        assertTrue(status.needsAttention)
    }

    @Test
    fun `blurring reports read blocked rather than missing folders when photos cannot be read`() {
        // Order matters: sending the parent to grant folders when the app cannot read a single photo
        // would send them to fix the wrong thing.
        val status = resolve(
            MediaReadAccess.DENIED,
            maximumProtectionOn = true,
            hasAnyFolderGrant = false
        )
        assertEquals(BlurEnforcement.READ_BLOCKED, status.blur)
    }

    // -----------------------------------------------------------------------------------
    // Blurring
    // -----------------------------------------------------------------------------------

    @Test
    fun `setting off is the default and needs no attention`() {
        // Maximum Protection ships off. Presenting that as a warning would turn the product's own
        // default into a permanent alarm.
        val status = resolve(MediaReadAccess.FULL, maximumProtectionOn = false)
        assertEquals(BlurEnforcement.SETTING_OFF, status.blur)
        assertFalse(status.needsAttention)
    }

    @Test
    fun `no folders are asked for while the setting is off`() {
        val status = resolve(MediaReadAccess.FULL, maximumProtectionOn = false)
        assertTrue(status.missingFolderTargets.isEmpty())
    }

    @Test
    fun `active when reading works, the setting is on and a folder is granted`() {
        val status = resolve(
            MediaReadAccess.FULL,
            maximumProtectionOn = true,
            hasAnyFolderGrant = true
        )
        assertEquals(BlurEnforcement.ACTIVE, status.blur)
        assertFalse(status.needsAttention)
    }

    @Test
    fun `no grant when the setting is on but no folder has been granted`() {
        val status = resolve(MediaReadAccess.FULL, maximumProtectionOn = true)
        assertEquals(BlurEnforcement.NO_GRANT, status.blur)
        assertTrue(status.needsAttention)
        assertEquals(listOf("CAMERA", "PICTURES"), status.missingFolderTargets)
    }

    @Test
    fun `revoked when a granted folder has since been turned off`() {
        val status = resolve(
            MediaReadAccess.FULL,
            maximumProtectionOn = true,
            hasAnyFolderGrant = false,
            anyGrantRevoked = true
        )
        assertEquals(BlurEnforcement.REVOKED, status.blur)
        assertTrue(status.needsAttention)
    }

    @Test
    fun `a partially granted set still counts as active`() {
        // One granted folder is worth having: images there get blurred while the rest stay askable.
        val status = resolve(
            MediaReadAccess.FULL,
            maximumProtectionOn = true,
            hasAnyFolderGrant = true,
            anyGrantRevoked = true,
            missing = listOf("PICTURES")
        )
        assertEquals(BlurEnforcement.ACTIVE, status.blur)
        assertEquals(listOf("PICTURES"), status.missingFolderTargets)
    }

    @Test
    fun `detection keeps working even when blurring does not`() {
        // The promise this feature must never break: losing folder access costs the blur, never the
        // detection and the alert.
        listOf(
            resolve(MediaReadAccess.FULL, maximumProtectionOn = true),
            resolve(MediaReadAccess.FULL, maximumProtectionOn = true, anyGrantRevoked = true),
            resolve(MediaReadAccess.FULL, maximumProtectionOn = false)
        ).forEach { status ->
            assertTrue("detection must survive blur being unavailable", status.detectionWorks)
        }
    }
}
