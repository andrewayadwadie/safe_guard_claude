package com.safeguard.parentalcontrol.util

import com.safeguard.parentalcontrol.util.MediaWriteAccess.GrantState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whether the app may modify a given image, and what the parent is told when it may not.
 *
 * Feature `016-remove-all-files-access`, contract C-1. The stakes here are specific: a state wrongly
 * reported as [GrantState.GRANTED] leads to an image being recorded as blurred when it was not, which
 * is the one dishonesty FR-016 forbids. The decision is a pure function precisely so these cases can
 * be pinned down without a device.
 */
class MediaWriteAccessGrantStateTest {

    private fun state(
        held: List<String> = emptyList(),
        path: String,
        completed: Set<String> = emptySet()
    ): GrantState = MediaTreePaths.resolveGrantState(held, path, completed)

    // -----------------------------------------------------------------------------------
    // GRANTED
    // -----------------------------------------------------------------------------------

    @Test
    fun `granted when a held tree covers the path`() {
        assertEquals(
            GrantState.GRANTED,
            state(held = listOf("primary:DCIM"), path = "DCIM/Camera/a.jpg")
        )
    }

    @Test
    fun `granted for a subfolder of a granted tree`() {
        assertEquals(
            GrantState.GRANTED,
            state(held = listOf("primary:Pictures"), path = "Pictures/Screenshots/shot.png")
        )
    }

    @Test
    fun `a current grant outranks a stale completion record`() {
        // Re-granting after a revocation must read as GRANTED, not stay REVOKED forever.
        assertEquals(
            GrantState.GRANTED,
            state(
                held = listOf("primary:DCIM"),
                path = "DCIM/Camera/a.jpg",
                completed = setOf("CAMERA")
            )
        )
    }

    // -----------------------------------------------------------------------------------
    // NOT_REQUESTED vs REVOKED — same effect on writes, different words to the parent
    // -----------------------------------------------------------------------------------

    @Test
    fun `not requested when the parent has never been asked`() {
        assertEquals(GrantState.NOT_REQUESTED, state(path = "DCIM/Camera/a.jpg"))
    }

    @Test
    fun `revoked when the parent once granted it and the grant is gone`() {
        assertEquals(
            GrantState.REVOKED,
            state(path = "DCIM/Camera/a.jpg", completed = setOf("CAMERA"))
        )
    }

    @Test
    fun `a completion record for a different folder does not make this one revoked`() {
        assertEquals(
            GrantState.NOT_REQUESTED,
            state(path = "Pictures/a.png", completed = setOf("CAMERA"))
        )
    }

    @Test
    fun `partially granted folders report independently`() {
        // The parent granted the camera folder and backed out of the pictures one. Camera protection
        // must keep working while Pictures stays askable — the partial result is kept, not discarded.
        val held = listOf("primary:DCIM")
        assertEquals(GrantState.GRANTED, state(held, "DCIM/Camera/a.jpg"))
        assertEquals(GrantState.NOT_REQUESTED, state(held, "Pictures/a.png"))
    }

    @Test
    fun `a descendant grant leaves the sibling branch ungranted`() {
        val held = listOf("primary:DCIM/Camera")
        assertEquals(GrantState.GRANTED, state(held, "DCIM/Camera/a.jpg"))
        assertEquals(GrantState.NOT_REQUESTED, state(held, "DCIM/Screenshots/a.png"))
    }

    // -----------------------------------------------------------------------------------
    // UNGRANTABLE
    // -----------------------------------------------------------------------------------

    @Test
    fun `the Download folder is ungrantable, not merely ungranted`() {
        // The distinction is what stops the app pestering the parent for a permission Android will
        // never issue, and what lets the alert explain why that image stays visible.
        assertEquals(GrantState.UNGRANTABLE, state(path = "Download/a.jpg"))
    }

    @Test
    fun `ungrantable wins over a completion record`() {
        assertEquals(
            GrantState.UNGRANTABLE,
            state(path = "Download/a.jpg", completed = setOf("CAMERA", "PICTURES"))
        )
    }

    @Test
    fun `another app's sandbox is ungrantable`() {
        assertEquals(GrantState.UNGRANTABLE, state(path = "Android/data/com.other/a.jpg"))
    }

    // -----------------------------------------------------------------------------------
    // Defensive cases
    // -----------------------------------------------------------------------------------

    @Test
    fun `a storage root grant confers nothing`() {
        // The platform never grants the root. If one appeared, treating it as universal access would
        // hand the app exactly the reach this feature exists to give up.
        assertEquals(
            GrantState.NOT_REQUESTED,
            state(held = listOf("primary:"), path = "DCIM/Camera/a.jpg")
        )
    }

    @Test
    fun `a malformed held tree id is ignored`() {
        assertEquals(
            GrantState.NOT_REQUESTED,
            state(held = listOf("garbage"), path = "DCIM/Camera/a.jpg")
        )
    }

    @Test
    fun `a watched folder outside every target is ungranted rather than revoked`() {
        assertEquals(
            GrantState.NOT_REQUESTED,
            state(path = "Documents/a.jpg", completed = setOf("CAMERA", "PICTURES"))
        )
    }
}
