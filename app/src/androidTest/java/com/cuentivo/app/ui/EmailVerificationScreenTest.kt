package com.cuentivo.app.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.cuentivo.app.ui.auth.EmailVerificationScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EmailVerificationScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun it_names_the_address_and_offers_the_three_ways_out() {
        var checked = 0
        var resent = 0
        var signedOut = 0
        composeRule.setContent {
            EmailVerificationScreen(
                email = "ana@ejemplo.com",
                isWorking = false,
                message = null,
                onCheck = { checked++ },
                onResend = { resent++ },
                onUseAnotherAccount = { signedOut++ },
            )
        }

        // The address is spelled out: a typo in it is the most likely reason no mail arrived.
        composeRule.onNodeWithText("ana@ejemplo.com", substring = true).assertIsDisplayed()
        listOf("verify-check", "verify-resend", "verify-sign-out").forEach { tag ->
            composeRule.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }

        composeRule.onNodeWithTag("verify-check").performClick()
        composeRule.onNodeWithTag("verify-resend").performClick()
        composeRule.onNodeWithTag("verify-sign-out").performClick()

        composeRule.runOnIdle {
            assertEquals(1, checked)
            assertEquals(1, resent)
            assertEquals(1, signedOut)
        }
    }

    /** Whatever went wrong is said here; a screen that only waits gives people nothing to do. */
    @Test fun a_pending_verification_says_so() {
        composeRule.setContent {
            EmailVerificationScreen(
                email = null,
                isWorking = false,
                message = "Todavía no aparece verificado.",
                onCheck = {},
                onResend = {},
                onUseAnotherAccount = {},
            )
        }

        composeRule.onNodeWithText("Todavía no aparece verificado.").assertIsDisplayed()
    }
}
