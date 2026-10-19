package com.safeguard.parentalcontrol.util

/**
 * What the parent is told about image protection, and the pure function that works it out.
 *
 * Kept free of Android framework types on purpose: this project has no Robolectric, so the only way
 * these decisions can be unit tested is if they are expressed over plain inputs. The framework
 * lookups (which permission is granted, which folders are held) happen in the caller and arrive here
 * as values.
 *
 * Feature: `specs/016-remove-all-files-access/` — data-model.md entity 4.
 */

/** How much of the child's photo library the app may read. */
enum class MediaReadAccess {
    /** The whole library, now and in future. The only state detection can work in. */
    FULL,

    /**
     * A hand-picked set of photos, from the Android 14 "Select photos" option.
     *
     * Deliberately **not** a success state: a photo the child receives tomorrow was not in
     * yesterday's selection, and seeing images that arrive later is the entire point of the feature.
     */
    PARTIAL,

    /** Nothing. Detection cannot run at all. */
    DENIED
}

/** Whether flagged images are being blurred in place, and why not when they are not. */
enum class BlurEnforcement {
    /** Blurring is on and folder access is held. */
    ACTIVE,

    /** Maximum Protection is off. The product default — a choice, not a problem to fix. */
    SETTING_OFF,

    /** Maximum Protection is on but the parent has not granted any picture folder yet. */
    NO_GRANT,

    /** Folder access was granted and has since been turned off or lost to a reinstall. */
    REVOKED,

    /** Images cannot even be read, so there is nothing to blur. Fix reading first. */
    READ_BLOCKED
}

/**
 * The complete parent-facing picture. Derived on each read; never stored.
 *
 * @param missingFolderTargets names of the picture folders still to grant, in the order to ask.
 */
data class ImageProtectionStatus(
    val read: MediaReadAccess,
    val blur: BlurEnforcement,
    val missingFolderTargets: List<String> = emptyList()
) {
    /** Detection, backup and parent alerts all work. The baseline promise of the feature. */
    val detectionWorks: Boolean get() = read == MediaReadAccess.FULL

    /** Whether anything needs the parent's attention. [BlurEnforcement.SETTING_OFF] does not. */
    val needsAttention: Boolean
        get() = !detectionWorks || blur == BlurEnforcement.NO_GRANT || blur == BlurEnforcement.REVOKED
}

object ImageProtectionStatusResolver {

    /**
     * Work out what to tell the parent.
     *
     * The ordering matters: reading is checked before blurring, because an app that cannot read a
     * photo has nothing to blur, and telling the parent to go grant folders in that state would send
     * them to fix the wrong thing.
     *
     * @param maximumProtectionOn the parent's Maximum Protection setting, default off.
     * @param hasAnyFolderGrant whether at least one picture folder is currently granted.
     * @param anyGrantRevoked whether a folder the parent completed is no longer held — which is what
     *   separates "never set up" from "was turned off" in the wording the parent sees.
     */
    fun resolve(
        read: MediaReadAccess,
        maximumProtectionOn: Boolean,
        hasAnyFolderGrant: Boolean,
        anyGrantRevoked: Boolean,
        missingFolderTargets: List<String> = emptyList()
    ): ImageProtectionStatus {
        val blur = when {
            read != MediaReadAccess.FULL -> BlurEnforcement.READ_BLOCKED
            !maximumProtectionOn -> BlurEnforcement.SETTING_OFF
            hasAnyFolderGrant -> BlurEnforcement.ACTIVE
            anyGrantRevoked -> BlurEnforcement.REVOKED
            else -> BlurEnforcement.NO_GRANT
        }

        return ImageProtectionStatus(
            read = read,
            blur = blur,
            // Nothing to ask for when the setting is off — the parent has not asked for blurring, so
            // pestering them for folder access would be asking for a permission with no purpose yet.
            missingFolderTargets = if (blur == BlurEnforcement.SETTING_OFF) emptyList()
            else missingFolderTargets
        )
    }
}
