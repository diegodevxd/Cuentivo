package com.cuentivo.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiDraftSafetyTest {

    private fun interpret(intent: AiIntent) = AiProviderResult.Interpreted(intent)

    @Test
    fun `valid payment draft is ready for human preview`() {
        val result = AiDraftSafety.validate(
            interpret(AiIntent.Draft.RecordPaymentDraft(customerName = "Diego", amountCents = 500L)),
        )

        assertEquals(
            AiDraftSafetyResult.ReadyForHumanPreview(
                AiIntent.Draft.RecordPaymentDraft(customerName = "Diego", amountCents = 500L),
            ),
            result,
        )
    }

    @Test
    fun `payment with zero or negative amount is never ready`() {
        assertEquals(
            AiDraftSafetyResult.NotReady,
            AiDraftSafety.validate(interpret(AiIntent.Draft.RecordPaymentDraft("Diego", 0L))),
        )
        assertEquals(
            AiDraftSafetyResult.NotReady,
            AiDraftSafety.validate(interpret(AiIntent.Draft.RecordPaymentDraft("Diego", -500L))),
        )
    }

    @Test
    fun `payment with blank customer name is never ready`() {
        listOf("", "   ", "\n").forEach { name ->
            assertEquals(
                AiDraftSafetyResult.NotReady,
                AiDraftSafety.validate(interpret(AiIntent.Draft.RecordPaymentDraft(name, 500L))),
            )
        }
    }

    @Test
    fun `valid sale draft is ready for human preview`() {
        val draft = AiIntent.Draft.CreateSaleDraft(
            customerName = "Diego",
            lines = listOf(AiSaleLineDraft("perfume", 2, 300L)),
            declaredPaymentCents = 200L,
        )

        assertEquals(AiDraftSafetyResult.ReadyForHumanPreview(draft), AiDraftSafety.validate(interpret(draft)))
    }

    @Test
    fun `sale with blank product or customer name is never ready`() {
        val blankProduct = AiIntent.Draft.CreateSaleDraft(
            customerName = "Diego",
            lines = listOf(AiSaleLineDraft("   ", 2, 300L)),
            declaredPaymentCents = 200L,
        )
        val blankCustomer = AiIntent.Draft.CreateSaleDraft(
            customerName = "",
            lines = listOf(AiSaleLineDraft("perfume", 2, 300L)),
            declaredPaymentCents = 200L,
        )

        assertEquals(AiDraftSafetyResult.NotReady, AiDraftSafety.validate(interpret(blankProduct)))
        assertEquals(AiDraftSafetyResult.NotReady, AiDraftSafety.validate(interpret(blankCustomer)))
    }

    @Test
    fun `sale with zero or negative quantity is never ready`() {
        listOf(0, -1).forEach { quantity ->
            val draft = AiIntent.Draft.CreateSaleDraft(
                customerName = "Diego",
                lines = listOf(AiSaleLineDraft("perfume", quantity, 300L)),
                declaredPaymentCents = 200L,
            )
            assertEquals(AiDraftSafetyResult.NotReady, AiDraftSafety.validate(interpret(draft)))
        }
    }

    @Test
    fun `sale with zero or negative unit price is never ready`() {
        listOf(0L, -300L).forEach { price ->
            val draft = AiIntent.Draft.CreateSaleDraft(
                customerName = "Diego",
                lines = listOf(AiSaleLineDraft("perfume", 2, price)),
                declaredPaymentCents = 200L,
            )
            assertEquals(AiDraftSafetyResult.NotReady, AiDraftSafety.validate(interpret(draft)))
        }
    }

    @Test
    fun `sale with zero or negative declared payment is never ready`() {
        listOf(0L, -200L).forEach { declared ->
            val draft = AiIntent.Draft.CreateSaleDraft(
                customerName = "Diego",
                lines = listOf(AiSaleLineDraft("perfume", 2, 300L)),
                declaredPaymentCents = declared,
            )
            assertEquals(AiDraftSafetyResult.NotReady, AiDraftSafety.validate(interpret(draft)))
        }
    }

    @Test
    fun `sale without lines is never ready`() {
        val draft = AiIntent.Draft.CreateSaleDraft(
            customerName = "Diego",
            lines = emptyList(),
            declaredPaymentCents = 200L,
        )

        assertEquals(AiDraftSafetyResult.NotReady, AiDraftSafety.validate(interpret(draft)))
    }

    @Test
    fun `clarification and refusal are never ready`() {
        assertEquals(AiDraftSafetyResult.NotReady, AiDraftSafety.validate(interpret(AiIntent.NeedClarification)))
        assertEquals(AiDraftSafetyResult.NotReady, AiDraftSafety.validate(interpret(AiIntent.Refuse)))
    }

    @Test
    fun `unavailable provider result is never ready`() {
        assertEquals(AiDraftSafetyResult.NotReady, AiDraftSafety.validate(AiProviderResult.Unavailable("provider down")))
    }
}
