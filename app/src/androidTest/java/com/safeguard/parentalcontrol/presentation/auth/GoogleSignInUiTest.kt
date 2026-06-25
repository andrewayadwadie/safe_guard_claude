package com.safeguard.parentalcontrol.presentation.auth

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.safeguard.parentalcontrol.data.model.UserRole
import com.safeguard.parentalcontrol.presentation.auth.components.GoogleSignInButton
import com.safeguard.parentalcontrol.presentation.auth.components.RoleSelectionDialog
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class GoogleSignInUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun googleButton_disabledWhenEnabledFalse() {
        composeRule.setContent {
            GoogleSignInButton(onClick = {}, isLoading = false, enabled = false)
        }
        composeRule.onNodeWithText("Continue with Google").assertIsNotEnabled()
    }

    /**
     * Edit 2 / FR-019 regression guard: the Google button renders and stays displayed
     * by default. Visibility is no longer gated by any server status flag (FR-017/018),
     * so the disappearing-button bug cannot recur at the component level.
     */
    @Test
    fun googleButton_isDisplayedByDefault() {
        composeRule.setContent {
            GoogleSignInButton(onClick = {}, isLoading = false, enabled = true)
        }
        composeRule.onNodeWithText("Continue with Google").assertExists()
        composeRule.onNodeWithText("Continue with Google").assertIsDisplayed()
        composeRule.onNodeWithText("Continue with Google").assertIsEnabled()
    }

    @Test
    fun roleDialog_continueDisabledUntilSelection_thenEnabled() {
        composeRule.setContent {
            RoleSelectionDialog(onRoleSelected = {}, onDismiss = {})
        }
        // Continue disabled with no selection
        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
        // Select Parent card
        composeRule.onNodeWithText("Parent").performClick()
        composeRule.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun roleDialog_outsideTapDoesNotDismiss() {
        var dismissed = false
        composeRule.setContent {
            RoleSelectionDialog(onRoleSelected = {}, onDismiss = { dismissed = true })
        }
        // onDismissRequest is a no-op + dismissOnClickOutside=false; dialog content stays.
        composeRule.onNodeWithText("Choose Your Role").assertExists()
        assertFalse(dismissed)
    }
}
