package com.safeguard.parentalcontrol.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The path arithmetic behind folder-access writes.
 *
 * Feature `016-remove-all-files-access`, contract C-4. These are the calculations that decide which
 * file the app is about to overwrite, so getting one wrong means blurring the wrong photo or
 * silently failing to blur the right one. There is no Robolectric in this project, which is exactly
 * why this logic lives in a pure object instead of inside the class that calls the platform.
 */
class MediaWriteAccessDocumentIdTest {

    private val root = "/storage/emulated/0"

    // -----------------------------------------------------------------------------------
    // relativeToExternalRoot
    // -----------------------------------------------------------------------------------

    @Test
    fun `strips the external storage root`() {
        assertEquals(
            "DCIM/Camera/IMG_0001.jpg",
            MediaTreePaths.relativeToExternalRoot("$root/DCIM/Camera/IMG_0001.jpg", root)
        )
    }

    @Test
    fun `tolerates a trailing slash on the root`() {
        assertEquals(
            "Pictures/a.png",
            MediaTreePaths.relativeToExternalRoot("$root/Pictures/a.png", "$root/")
        )
    }

    @Test
    fun `rejects a path outside external storage`() {
        // App-private storage and other volumes are not reachable through a picture-folder grant.
        assertNull(MediaTreePaths.relativeToExternalRoot("/data/user/0/com.app/files/x.jpg", root))
    }

    @Test
    fun `rejects the root itself`() {
        assertNull(MediaTreePaths.relativeToExternalRoot(root, root))
    }

    @Test
    fun `does not treat a sibling directory as being under the root`() {
        // "/storage/emulated/0abc" starts with the root as a STRING but is a different directory.
        assertNull(MediaTreePaths.relativeToExternalRoot("/storage/emulated/0abc/DCIM/a.jpg", root))
    }

    // -----------------------------------------------------------------------------------
    // isSameOrBeneath — segment-wise, never substring
    // -----------------------------------------------------------------------------------

    @Test
    fun `a folder contains itself`() {
        assertTrue(MediaTreePaths.isSameOrBeneath(parent = "DCIM", candidate = "DCIM"))
    }

    @Test
    fun `a folder contains its descendants`() {
        assertTrue(MediaTreePaths.isSameOrBeneath("DCIM", "DCIM/Camera"))
        assertTrue(MediaTreePaths.isSameOrBeneath("DCIM", "DCIM/Screenshots/shot.png"))
    }

    @Test
    fun `a similarly named sibling is not a descendant`() {
        // The reason comparison is segment-wise: a grant on DCIM must not appear to cover DCIMX.
        assertFalse(MediaTreePaths.isSameOrBeneath("DCIM", "DCIMX/a.jpg"))
        assertFalse(MediaTreePaths.isSameOrBeneath("Pictures", "Pictures2/a.jpg"))
    }

    @Test
    fun `a child does not contain its parent`() {
        assertFalse(MediaTreePaths.isSameOrBeneath("DCIM/Camera", "DCIM"))
    }

    @Test
    fun `an empty parent contains nothing`() {
        // An empty relative path is the storage root, which is never a real grant.
        assertFalse(MediaTreePaths.isSameOrBeneath("", "DCIM/a.jpg"))
    }

    // -----------------------------------------------------------------------------------
    // Tree document ids
    // -----------------------------------------------------------------------------------

    @Test
    fun `reads the relative path out of a tree id`() {
        assertEquals("DCIM", MediaTreePaths.relativePathOfTree("primary:DCIM"))
        assertEquals("DCIM/Camera", MediaTreePaths.relativePathOfTree("primary:DCIM/Camera"))
    }

    @Test
    fun `the storage root is not a usable tree`() {
        // The platform refuses to grant the root. If one ever appears it must read as no grant at
        // all, never as access to everything.
        assertFalse(MediaTreePaths.isUsableTree("primary:"))
        assertFalse(MediaTreePaths.isUsableTree("primary:/"))
    }

    @Test
    fun `a malformed tree id is not usable`() {
        assertFalse(MediaTreePaths.isUsableTree("nonsense"))
    }

    @Test
    fun `a real folder tree is usable`() {
        assertTrue(MediaTreePaths.isUsableTree("primary:Pictures"))
    }

    // -----------------------------------------------------------------------------------
    // coveringTree
    // -----------------------------------------------------------------------------------

    @Test
    fun `an ancestor tree covers the path`() {
        assertEquals(
            "primary:DCIM",
            MediaTreePaths.coveringTree(listOf("primary:DCIM"), "DCIM/Camera/a.jpg")
        )
    }

    @Test
    fun `a descendant tree does not cover a sibling branch`() {
        // A parent who picked DCIM/Camera gets camera images and NOT screenshots. The partial win is
        // real and must not be mistaken for full coverage of DCIM.
        assertNull(
            MediaTreePaths.coveringTree(listOf("primary:DCIM/Camera"), "DCIM/Screenshots/a.png")
        )
        assertEquals(
            "primary:DCIM/Camera",
            MediaTreePaths.coveringTree(listOf("primary:DCIM/Camera"), "DCIM/Camera/a.jpg")
        )
    }

    @Test
    fun `the most specific covering tree wins`() {
        assertEquals(
            "primary:DCIM/Camera",
            MediaTreePaths.coveringTree(
                listOf("primary:DCIM", "primary:DCIM/Camera"),
                "DCIM/Camera/a.jpg"
            )
        )
    }

    @Test
    fun `an unusable tree is ignored even when it would match`() {
        assertNull(MediaTreePaths.coveringTree(listOf("primary:"), "DCIM/Camera/a.jpg"))
    }

    @Test
    fun `no held trees means no coverage`() {
        assertFalse(MediaTreePaths.coversRelativePath(emptyList(), "DCIM/a.jpg"))
    }

    // -----------------------------------------------------------------------------------
    // childDocumentId
    // -----------------------------------------------------------------------------------

    @Test
    fun `builds a child document id from the tree and the relative path`() {
        assertEquals(
            "primary:DCIM/Camera/IMG_0001.jpg",
            MediaTreePaths.childDocumentId("primary:DCIM", "DCIM/Camera/IMG_0001.jpg")
        )
    }

    @Test
    fun `keeps the volume of the granted tree`() {
        // A grant on a secondary volume must resolve against that volume, not be rewritten to primary.
        assertEquals(
            "1234-ABCD:DCIM/a.jpg",
            MediaTreePaths.childDocumentId("1234-ABCD:DCIM", "DCIM/a.jpg")
        )
    }

    @Test
    fun `refuses to build an id outside the granted tree`() {
        assertNull(MediaTreePaths.childDocumentId("primary:DCIM", "Pictures/a.jpg"))
    }

    @Test
    fun `handles names that need escaping without mangling them`() {
        // Escaping is the URI layer's job; this stage must pass the name through untouched, spaces,
        // ampersands, percent signs, non-Latin script and all.
        val awkward = "DCIM/Camera/holiday 100% #1 & co %20 صورة.jpg"
        assertEquals("primary:$awkward", MediaTreePaths.childDocumentId("primary:DCIM", awkward))
    }

    // -----------------------------------------------------------------------------------
    // Ungrantable locations
    // -----------------------------------------------------------------------------------

    @Test
    fun `the Download folder is ungrantable`() {
        // From Android 11 the picker refuses it, which is the one capability this feature gives up.
        assertTrue(MediaTreePaths.isUngrantable("Download"))
        assertTrue(MediaTreePaths.isUngrantable("Download/sub/a.jpg"))
    }

    @Test
    fun `app sandbox directories are ungrantable`() {
        assertTrue(MediaTreePaths.isUngrantable("Android/data/com.other.app/file.jpg"))
        assertTrue(MediaTreePaths.isUngrantable("Android/obb/x"))
    }

    @Test
    fun `picture folders are grantable`() {
        assertFalse(MediaTreePaths.isUngrantable("DCIM/Camera/a.jpg"))
        assertFalse(MediaTreePaths.isUngrantable("Pictures/Screenshots/a.png"))
        assertFalse(MediaTreePaths.isUngrantable("Screenshots/a.png"))
    }

    @Test
    fun `a folder merely starting with Download is still grantable`() {
        assertFalse(MediaTreePaths.isUngrantable("Downloads2/a.jpg"))
    }

    @Test
    fun `media folders of other apps are not in the ungrantable set`() {
        // Android/media is not restricted the way Android/data is. No folder there is watched today,
        // so this only records that the restriction list is not over-broad.
        assertFalse(MediaTreePaths.isUngrantable("Android/media/com.whatsapp/x.jpg"))
    }

    // -----------------------------------------------------------------------------------
    // targetFor
    // -----------------------------------------------------------------------------------

    @Test
    fun `maps paths to the folder the parent would grant`() {
        assertEquals("CAMERA", MediaTreePaths.targetFor("DCIM/Camera/a.jpg"))
        assertEquals("CAMERA", MediaTreePaths.targetFor("DCIM/Screenshots/a.png"))
        assertEquals("PICTURES", MediaTreePaths.targetFor("Pictures/a.png"))
        assertEquals("ROOT_SCREENSHOTS", MediaTreePaths.targetFor("Screenshots/a.png"))
    }

    @Test
    fun `a path in no watched folder maps to no target`() {
        assertNull(MediaTreePaths.targetFor("Documents/a.jpg"))
        assertNull(MediaTreePaths.targetFor("Download/a.jpg"))
    }
}
