package com.safeguard.parentalcontrol.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the automation / bypass-tool matcher used by tamper detection.
 *
 * The load-bearing guarantee: the known auto-clicker / key-remap / macro tools a child uses to
 * defeat the lock screen (Key Mapper, Auto Clicker, MacroDroid, Tasker, Automate and their many
 * store clones) are recognised, while ordinary apps and legitimate assistive accessibility
 * services (TalkBack, password managers, banking apps) are NOT flagged - a false positive here
 * would nag the parent about every harmless accessibility service.
 */
class AccessibilityServiceHelperTest {

    @Test
    fun `flags the tools actually seen on the child device`() {
        // The two enabled on device id=48 during the 2026-06-29 investigation.
        assertTrue(AccessibilityServiceHelper.isAutomationToolPackage("io.github.sds100.keymapper"))
        assertTrue(AccessibilityServiceHelper.isAutomationToolPackage("com.truedevelopersstudio.automatictap.autoclicker"))
    }

    @Test
    fun `flags known automation and macro tools`() {
        assertTrue(AccessibilityServiceHelper.isAutomationToolPackage("com.arlosoft.macrodroid"))
        assertTrue(AccessibilityServiceHelper.isAutomationToolPackage("net.dinglisch.android.taskerm"))
        assertTrue(AccessibilityServiceHelper.isAutomationToolPackage("com.llamalab.automate"))
    }

    @Test
    fun `flags auto-clicker clones via keyword`() {
        // Store is full of these - keyword match must catch unknown packages too.
        assertTrue(AccessibilityServiceHelper.isAutomationToolPackage("com.example.autoclicker.pro"))
        assertTrue(AccessibilityServiceHelper.isAutomationToolPackage("org.someone.AutoClick"))
        assertTrue(AccessibilityServiceHelper.isAutomationToolPackage("com.fast.auto.tap.tool"))
        assertTrue(AccessibilityServiceHelper.isAutomationToolPackage("com.macro.recorder.x"))
    }

    @Test
    fun `does not flag ordinary or assistive apps`() {
        assertFalse(AccessibilityServiceHelper.isAutomationToolPackage("com.safeguard.parentalcontrol"))
        assertFalse(AccessibilityServiceHelper.isAutomationToolPackage("com.google.android.marvin.talkback"))
        assertFalse(AccessibilityServiceHelper.isAutomationToolPackage("com.x8bit.bitwarden"))
        assertFalse(AccessibilityServiceHelper.isAutomationToolPackage("com.whatsapp"))
        assertFalse(AccessibilityServiceHelper.isAutomationToolPackage("com.instagram.android"))
        assertFalse(AccessibilityServiceHelper.isAutomationToolPackage("com.android.chrome"))
    }
}
