package com.safeguard.parentalcontrol.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only place in the app that writes to or deletes from the child's shared storage.
 *
 * Replaces the app's former dependency on all-files access, which Google Play restricts to file
 * managers, backup/restore, anti-virus, document management, on-device search, disk encryption and
 * device migration — parental control is not a permitted use. Writes now go through folder access
 * the parent grants once per picture folder through the system document picker.
 *
 * Deliberate properties, each load-bearing:
 *
 * - **Holds no state.** The platform's persisted-permission list is the only authority on what the
 *   app may write. A cached "granted" that the device owner has since revoked would make the app
 *   report an image as blurred when it was not, which is exactly the dishonesty the spec forbids.
 * - **Needs no Activity.** Writes happen inside `ImageScanWorker` and `MediaFileObserver`, neither of
 *   which has one. This is why `MANAGE_MEDIA` was rejected during planning: its write path returns an
 *   intent sender that must be launched from an Activity.
 * - **Never walks a tree.** A document id is derived arithmetically from the file's path relative to
 *   external storage, so resolving an image in a camera folder holding thousands of photos costs the
 *   same as resolving one in an empty folder.
 *
 * Contract: `specs/016-remove-all-files-access/contracts/media-write-access.md`
 */
@Singleton
class MediaWriteAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) {
    companion object {
        private const val TAG = "MediaWriteAccess"

        /** The platform provider that backs external-storage tree grants. */
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }

    /**
     * A picture folder the parent can grant, identified by its path relative to external storage.
     *
     * These are tree roots, so one grant covers every watched folder beneath it: [CAMERA] covers
     * `DCIM/Camera` and `DCIM/Screenshots`, and [PICTURES] covers `Pictures/Screenshots`.
     *
     * The `Download` folder is deliberately absent — from Android 11 the platform refuses to grant
     * it (along with the storage root and reliable SD-card roots), so it is modelled as
     * [GrantState.UNGRANTABLE] rather than as a target that could be asked for.
     */
    enum class GrantTarget(val relativePath: String) {
        CAMERA("DCIM"),
        PICTURES("Pictures"),

        /**
         * Some manufacturers put screenshots in a folder directly off the storage root instead of
         * under `DCIM` or `Pictures`. The folder itself is grantable — only the root is refused —
         * but it exists on a minority of devices, so it is offered only where [exists] is true and
         * never blocks setup completion.
         */
        ROOT_SCREENSHOTS("Screenshots");

        companion object {
            /** Targets that should be offered on this device, evaluated now rather than cached. */
            fun offerable(): List<GrantTarget> = entries.filter { target ->
                target != ROOT_SCREENSHOTS || target.exists()
            }
        }

        fun exists(): Boolean =
            java.io.File(Environment.getExternalStorageDirectory(), relativePath).isDirectory
    }

    /** Whether the app may currently modify images in a given location, and why not when it may not. */
    enum class GrantState {
        /** Folder access is held. Writes proceed with no prompt. */
        GRANTED,

        /** The parent has never completed the picker for this folder. */
        NOT_REQUESTED,

        /**
         * The parent granted this folder and the grant is gone — revoked in system settings, or lost
         * to an app reinstall. Behaves identically to [NOT_REQUESTED] at the write site and differs
         * only in what the parent is told.
         */
        REVOKED,

        /** The platform will not grant this folder at all. The `Download` folder, specifically. */
        UNGRANTABLE
    }

    /** Outcome of a write or delete. Every failure is distinguishable so the parent can be told why. */
    sealed class WriteResult {
        data object Success : WriteResult()

        /** No folder access covers this path. Carries the state so the caller can pick its wording. */
        data class NoAccess(val state: GrantState) : WriteResult()

        /** The document is not there — moved, renamed or deleted since it was detected. */
        data object DocumentMissing : WriteResult()

        /** The write threw. Out of space, grant revoked mid-write, provider failure. */
        data class Failed(val message: String) : WriteResult()

        /** The write completed without throwing but the result does not match what was written. */
        data class VerificationFailed(val expectedBytes: Long, val actualBytes: Long) : WriteResult()
    }

    // ---------------------------------------------------------------------------------------
    // C-1 — report grant state
    // ---------------------------------------------------------------------------------------

    /**
     * Document ids of every tree the app currently holds a persisted grant for, newly read from the
     * platform on every call. Not cached — see the class comment.
     */
    private fun heldTreeDocumentIds(): List<String> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission && it.uri.authority == EXTERNAL_STORAGE_AUTHORITY }
            .mapNotNull { permission ->
                runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull()
            }

    /** Whether folder access covering [target] is held. */
    fun grantState(target: GrantTarget): GrantState = MediaTreePaths.resolveGrantState(
        heldTreeDocumentIds = heldTreeDocumentIds(),
        relativePath = target.relativePath,
        completedTargetNames = preferencesManager.completedFolderGrantTargets
    )

    /**
     * Whether the app may modify the image at [absolutePath].
     *
     * Returns [GrantState.UNGRANTABLE] for a path the platform will never let the app write — the
     * `Download` folder, or anywhere outside external storage — so a caller can tell "ask the parent"
     * apart from "never ask".
     */
    fun grantStateForPath(absolutePath: String): GrantState {
        val relativePath = MediaTreePaths.relativeToExternalRoot(absolutePath, externalRoot())
            ?: return GrantState.UNGRANTABLE

        return MediaTreePaths.resolveGrantState(
            heldTreeDocumentIds = heldTreeDocumentIds(),
            relativePath = relativePath,
            completedTargetNames = preferencesManager.completedFolderGrantTargets
        )
    }

    /** Targets that exist on this device and still need a grant, in the order to ask for them. */
    fun missingTargets(): List<GrantTarget> {
        val held = heldTreeDocumentIds()
        return GrantTarget.offerable().filter {
            !MediaTreePaths.coversRelativePath(held, it.relativePath)
        }
    }

    /**
     * Whether access is held for at least one folder the app actually watches.
     *
     * Deliberately not "any persisted grant at all": a grant on some unrelated folder would then make
     * blurring report itself as active while no flagged image could be touched, which is the same lie
     * as marking an image blurred when it was not. Coverage of a real target is the only thing that
     * makes blurring possible.
     */
    fun hasAnyGrant(): Boolean {
        val held = heldTreeDocumentIds()
        return GrantTarget.entries.any {
            MediaTreePaths.coversRelativePath(held, it.relativePath)
        }
    }

    // ---------------------------------------------------------------------------------------
    // C-2 — build the picker request
    // ---------------------------------------------------------------------------------------

    /**
     * The intent that opens the system folder picker for [target], pre-pointed at that folder so the
     * parent does not have to navigate to it.
     *
     * Launched by the caller through an activity-result contract; this class launches nothing, which
     * is what keeps it usable from a worker and a service.
     */
    fun pickerIntent(target: GrantTarget): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                initialUriFor(target)?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
        }

    private fun initialUriFor(target: GrantTarget): Uri? = runCatching {
        DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_AUTHORITY,
            "${MediaTreePaths.PRIMARY_VOLUME}:${target.relativePath}"
        )
    }.getOrNull()

    // ---------------------------------------------------------------------------------------
    // C-3 — persist a grant returned by the picker
    // ---------------------------------------------------------------------------------------

    /**
     * Take a persistable hold on [treeUri] and report which targets it actually covers.
     *
     * The parent may navigate away from where the picker opened, so the returned list is what was
     * really granted rather than what was asked for. An empty list means the chosen folder covers
     * nothing the app watches — including the case where the parent somehow produced the storage
     * root, which is recognised as no grant rather than as a universal one.
     *
     * Idempotent: re-granting an already-held tree is not an error.
     */
    fun persistGrant(treeUri: Uri): List<GrantTarget> {
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (treeDocumentId == null || !MediaTreePaths.isUsableTree(treeDocumentId)) {
            Timber.w("$TAG: Ignoring an unusable folder grant")
            return emptyList()
        }

        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Timber.e(e, "$TAG: Could not persist the folder grant")
            return emptyList()
        }

        val covered = GrantTarget.entries.filter {
            MediaTreePaths.coversRelativePath(listOf(treeDocumentId), it.relativePath)
        }

        if (covered.isNotEmpty()) {
            preferencesManager.completedFolderGrantTargets =
                preferencesManager.completedFolderGrantTargets + covered.map { it.name }
        }

        Timber.i("$TAG: Folder grant persisted, covering ${covered.joinToString { it.name }}")
        return covered
    }

    // ---------------------------------------------------------------------------------------
    // C-4, C-5 — resolve a document and replace its bytes
    // ---------------------------------------------------------------------------------------

    /**
     * Replace the contents of the image at [absolutePath] with whatever [writer] emits.
     *
     * Takes a writer rather than a byte array so a multi-megabyte photo is streamed instead of being
     * held in memory twice. The bytes are counted as they pass through, and the document's size is
     * read back afterwards and compared: a stream that closed without throwing is not proof that
     * anything landed, and the caller marks an image as blurred only on a verified write.
     *
     * The caller is responsible for having a verified backup in place **before** calling this:
     * opening a document for writing truncates it, so a failure part-way through leaves a truncated
     * file where the child's photo was. `ImageBlurManager` owns that ordering and the
     * restore-on-failure branch.
     */
    fun write(absolutePath: String, writer: (java.io.OutputStream) -> Unit): WriteResult {
        val documentUri = resolveDocumentUri(absolutePath)
            ?: return WriteResult.NoAccess(grantStateForPath(absolutePath))

        if (!documentExists(documentUri)) return WriteResult.DocumentMissing

        var counted = 0L
        try {
            val stream = context.contentResolver.openOutputStream(documentUri, "wt")
                ?: return WriteResult.Failed("Could not open the image for writing")
            stream.use { out ->
                val counting = CountingOutputStream(out)
                writer(counting)
                counting.flush()
                counted = counting.bytesWritten
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Write failed")
            return WriteResult.Failed(e.message ?: "Write failed")
        }

        if (counted <= 0L) return WriteResult.VerificationFailed(counted, documentSize(documentUri))

        val written = documentSize(documentUri)
        if (written != counted) return WriteResult.VerificationFailed(counted, written)
        return WriteResult.Success
    }

    /** Counts bytes on their way to the real stream so a write can be verified against the result. */
    private class CountingOutputStream(
        private val delegate: java.io.OutputStream
    ) : java.io.OutputStream() {
        var bytesWritten: Long = 0
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() = delegate.flush()
    }

    // ---------------------------------------------------------------------------------------
    // C-6 — delete a document
    // ---------------------------------------------------------------------------------------

    /**
     * Delete the image at [absolutePath].
     *
     * A document that is already gone reports [WriteResult.Success]: the desired end state holds, and
     * telling the parent a deletion failed because the file had already gone would be a lie in the
     * other direction.
     */
    fun deleteDocument(absolutePath: String): WriteResult {
        val documentUri = resolveDocumentUri(absolutePath)
            ?: return WriteResult.NoAccess(grantStateForPath(absolutePath))

        if (!documentExists(documentUri)) return WriteResult.Success

        return try {
            if (DocumentsContract.deleteDocument(context.contentResolver, documentUri)) {
                WriteResult.Success
            } else {
                WriteResult.Failed("The system refused to delete the image")
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Delete failed")
            WriteResult.Failed(e.message ?: "Delete failed")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private fun externalRoot(): String =
        runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrDefault("/sdcard")

    /** Null when no held grant covers the path, which the caller turns into a NoAccess result. */
    private fun resolveDocumentUri(absolutePath: String): Uri? {
        val relativePath = MediaTreePaths.relativeToExternalRoot(absolutePath, externalRoot())
            ?: return null
        val treeDocumentId = MediaTreePaths.coveringTree(heldTreeDocumentIds(), relativePath)
            ?: return null
        val childDocumentId = MediaTreePaths.childDocumentId(treeDocumentId, relativePath)
            ?: return null

        return runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, treeDocumentId),
                childDocumentId
            )
        }.getOrNull()
    }

    private fun documentExists(documentUri: Uri): Boolean = queryDocument(documentUri) != null

    private fun documentSize(documentUri: Uri): Long = queryDocument(documentUri) ?: -1L

    /** Size of the document, or null when it does not exist. */
    private fun queryDocument(documentUri: Uri): Long? = try {
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    } catch (e: Exception) {
        Timber.w(e, "$TAG: Could not query the document")
        null
    }
}

/**
 * The path arithmetic behind [MediaWriteAccess], kept as pure functions over strings.
 *
 * This project has no Robolectric, so nothing that touches a framework class can be unit tested.
 * Separating the decisions — which tree covers which path, what a child document id looks like,
 * whether a folder can be granted at all — from the platform calls is what makes them testable, and
 * those decisions are where the bugs would be.
 */
internal object MediaTreePaths {

    const val PRIMARY_VOLUME = "primary"

    /**
     * Folders the platform refuses to grant through the document picker from Android 11 onward.
     *
     * The storage root is handled separately (an empty relative path), and `Android/data` and
     * `Android/obb` are listed for completeness even though the app never writes there.
     */
    private val UNGRANTABLE_ROOTS = listOf("Download", "Android/data", "Android/obb")

    /** `/storage/emulated/0/DCIM/Camera/a.jpg` → `DCIM/Camera/a.jpg`. Null when outside the root. */
    fun relativeToExternalRoot(absolutePath: String, externalRoot: String): String? {
        val normalisedPath = absolutePath.trimEnd('/')
        val normalisedRoot = externalRoot.trimEnd('/')
        if (!normalisedPath.startsWith("$normalisedRoot/")) return null
        return normalisedPath.removePrefix("$normalisedRoot/").trim('/').ifEmpty { null }
    }

    /** Whether a relative path sits somewhere the platform will not grant. */
    fun isUngrantable(relativePath: String): Boolean =
        UNGRANTABLE_ROOTS.any { isSameOrBeneath(parent = it, candidate = relativePath) }

    /**
     * Whether a tree grant is worth anything.
     *
     * A document id of `primary:` is the storage root, which the platform never grants — if one ever
     * appears it is treated as no grant rather than as access to everything.
     */
    fun isUsableTree(treeDocumentId: String): Boolean =
        relativePathOfTree(treeDocumentId)?.isNotEmpty() == true

    /** `primary:DCIM` → `DCIM`. Null when the id is not of the expected `volume:path` shape. */
    fun relativePathOfTree(treeDocumentId: String): String? {
        val separator = treeDocumentId.indexOf(':')
        if (separator < 0) return null
        return treeDocumentId.substring(separator + 1).trim('/')
    }

    private fun volumeOfTree(treeDocumentId: String): String? {
        val separator = treeDocumentId.indexOf(':')
        if (separator < 0) return null
        return treeDocumentId.substring(0, separator)
    }

    /**
     * Whether [candidate] is [parent] itself or sits beneath it, compared segment by segment.
     *
     * Segment-wise so that a grant on `DCIM` does not appear to cover a folder called `DCIMX`.
     */
    fun isSameOrBeneath(parent: String, candidate: String): Boolean {
        val parentSegments = parent.trim('/').split('/').filter { it.isNotEmpty() }
        val candidateSegments = candidate.trim('/').split('/').filter { it.isNotEmpty() }
        if (parentSegments.isEmpty() || parentSegments.size > candidateSegments.size) return false
        return parentSegments.indices.all { parentSegments[it] == candidateSegments[it] }
    }

    /**
     * The held tree that covers [relativePath], or null when none does.
     *
     * A tree that is an ancestor of the path counts: a `DCIM` grant covers `DCIM/Screenshots/a.png`.
     * A tree that is a descendant does not: a `DCIM/Camera` grant covers nothing in
     * `DCIM/Screenshots`. The most specific covering tree is returned so the shortest document id is
     * built.
     */
    fun coveringTree(heldTreeDocumentIds: List<String>, relativePath: String): String? =
        heldTreeDocumentIds
            .filter { isUsableTree(it) }
            .filter { treeId ->
                val treePath = relativePathOfTree(treeId) ?: return@filter false
                isSameOrBeneath(parent = treePath, candidate = relativePath)
            }
            .maxByOrNull { relativePathOfTree(it)?.length ?: 0 }

    /** Whether any held tree covers [relativePath]. */
    fun coversRelativePath(heldTreeDocumentIds: List<String>, relativePath: String): Boolean =
        coveringTree(heldTreeDocumentIds, relativePath) != null

    /**
     * The document id of the file at [relativePath] inside [treeDocumentId].
     *
     * Built arithmetically — `primary:DCIM` plus `DCIM/Camera/a.jpg` becomes
     * `primary:DCIM/Camera/a.jpg` — rather than by listing the tree, so the cost does not grow with
     * the number of images in the folder. The volume is taken from the tree's own id, so a grant on a
     * secondary volume resolves correctly without special-casing.
     */
    fun childDocumentId(treeDocumentId: String, relativePath: String): String? {
        val volume = volumeOfTree(treeDocumentId) ?: return null
        val treePath = relativePathOfTree(treeDocumentId) ?: return null
        if (!isSameOrBeneath(parent = treePath, candidate = relativePath)) return null
        return "$volume:${relativePath.trim('/')}"
    }

    /** The grant target a relative path belongs to, by name, or null when it belongs to none. */
    fun targetFor(relativePath: String): String? = MediaWriteAccess.GrantTarget.entries
        .firstOrNull { isSameOrBeneath(parent = it.relativePath, candidate = relativePath) }
        ?.name

    /**
     * The whole grant decision for one relative path, as a pure function.
     *
     * Everything the decision needs is a parameter so it can be tested without a device: what the
     * platform currently holds, the path in question, and which targets the parent has previously
     * completed the picker for. That last one is the only reason `REVOKED` can be told apart from
     * `NOT_REQUESTED` — the app never mirrors the grants themselves, so without a record of what was
     * once answered there would be no way to say "access was turned off" rather than "never set up".
     */
    fun resolveGrantState(
        heldTreeDocumentIds: List<String>,
        relativePath: String,
        completedTargetNames: Set<String>
    ): MediaWriteAccess.GrantState {
        if (isUngrantable(relativePath)) return MediaWriteAccess.GrantState.UNGRANTABLE
        if (coversRelativePath(heldTreeDocumentIds, relativePath)) {
            return MediaWriteAccess.GrantState.GRANTED
        }

        val target = targetFor(relativePath)
        return if (target != null && completedTargetNames.contains(target)) {
            MediaWriteAccess.GrantState.REVOKED
        } else {
            MediaWriteAccess.GrantState.NOT_REQUESTED
        }
    }
}
