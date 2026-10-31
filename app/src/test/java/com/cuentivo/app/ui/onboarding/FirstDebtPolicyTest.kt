package com.cuentivo.app.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstDebtPolicyTest {

    @Test
    fun `shouldShow returns true when empty customers, not dismissed, and no activity`() {
        assertTrue(
            FirstDebtPolicy.shouldShow(
                customerCount = 0,
                isDismissed = false,
                hasFinancialActivity = false,
            )
        )
    }

    @Test
    fun `shouldShow returns false when already dismissed or skipped`() {
        assertFalse(
            FirstDebtPolicy.shouldShow(
                customerCount = 0,
                isDismissed = true,
                hasFinancialActivity = false,
            )
        )
    }

    @Test
    fun `shouldShow returns false when customers already exist`() {
        assertFalse(
            FirstDebtPolicy.shouldShow(
                customerCount = 1,
                isDismissed = false,
                hasFinancialActivity = false,
            )
        )
        assertFalse(
            FirstDebtPolicy.shouldShow(
                customerCount = 10,
                isDismissed = false,
                hasFinancialActivity = false,
            )
        )
    }

    @Test
    fun `shouldShow returns false when financial activity already exists`() {
        assertFalse(
            FirstDebtPolicy.shouldShow(
                customerCount = 0,
                isDismissed = false,
                hasFinancialActivity = true,
            )
        )
    }

    @Test
    fun `canSubmit returns true for non-blank name and valid positive amount`() {
        assertTrue(FirstDebtPolicy.canSubmit(name = "Maria Lopez", amountText = "500"))
        assertTrue(FirstDebtPolicy.canSubmit(name = " Carlos ", amountText = " 120.50 "))
        assertTrue(FirstDebtPolicy.canSubmit(name = "Jose", amountText = "1"))
    }

    @Test
    fun `canSubmit returns false for blank name`() {
        assertFalse(FirstDebtPolicy.canSubmit(name = "", amountText = "500"))
        assertFalse(FirstDebtPolicy.canSubmit(name = "   ", amountText = "500"))
    }

    @Test
    fun `canSubmit returns false for invalid zero or negative amounts`() {
        assertFalse(FirstDebtPolicy.canSubmit(name = "Maria", amountText = ""))
        assertFalse(FirstDebtPolicy.canSubmit(name = "Maria", amountText = "0"))
        assertFalse(FirstDebtPolicy.canSubmit(name = "Maria", amountText = "-50"))
        assertFalse(FirstDebtPolicy.canSubmit(name = "Maria", amountText = "abc"))
    }

    @Test
    fun `canSubmit returns false while saving is in progress`() {
        assertFalse(
            FirstDebtPolicy.canSubmit(
                name = "Maria Lopez",
                amountText = "500",
                isSaving = true,
            )
        )
    }
}