package com.cuentivo.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class AiContractsTest {

    @Test
    fun `interpret request holds text and requestId`() {
        val request = AiInterpretRequest(text = "Diego me pagó 500", requestId = "req-1")

        assertEquals("Diego me pagó 500", request.text)
        assertEquals("req-1", request.requestId)
    }

    @Test
    fun `payment draft money is Long cents`() {
        val draft = AiIntent.Draft.RecordPaymentDraft(customerName = "Diego", amountCents = 500L)

        // Compile-time gate: the property must be assignable to Long.
        val amount: Long = draft.amountCents
        assertEquals(500L, amount)
        // Runtime gate: the value boxes as java.lang.Long, never Double/Float/Int.
        assertEquals(java.lang.Long::class.java, (draft.amountCents as Any).javaClass)
    }

    @Test
    fun `sale draft quantities are Int and money is Long cents`() {
        val draft = AiIntent.Draft.CreateSaleDraft(
            customerName = "Diego",
            lines = listOf(AiSaleLineDraft(productName = "perfume", quantity = 2, unitPriceCents = 300L)),
            declaredPaymentCents = 200L,
        )
        val line = draft.lines.single()

        val quantity: Int = line.quantity
        val unitPrice: Long = line.unitPriceCents
        val declaredPayment: Long = draft.declaredPaymentCents

        assertEquals(2, quantity)
        assertEquals(300L, unitPrice)
        assertEquals(200L, declaredPayment)
        assertEquals(java.lang.Integer::class.java, (line.quantity as Any).javaClass)
        assertEquals(java.lang.Long::class.java, (line.unitPriceCents as Any).javaClass)
        assertEquals(java.lang.Long::class.java, (draft.declaredPaymentCents as Any).javaClass)
    }

    @Test
    fun `drafts are immutable and copy produces an independent instance`() {
        val original = AiIntent.Draft.RecordPaymentDraft(customerName = "Diego", amountCents = 500L)
        val changed = original.copy(amountCents = 600L)

        assertNotSame(original, changed)
        assertEquals(500L, original.amountCents)
        assertEquals("Diego", changed.customerName)
        assertEquals(600L, changed.amountCents)
    }

    @Test
    fun `provider result carries an intent or an unavailability reason`() {
        val interpreted = AiProviderResult.Interpreted(AiIntent.NeedClarification)
        val unavailable = AiProviderResult.Unavailable("provider down")

        assertEquals(AiIntent.NeedClarification, interpreted.intent)
        assertEquals("provider down", unavailable.reason)
    }

    @Test
    fun `sale draft isolates its lines from the caller's mutable list`() {
        val callerLines = mutableListOf(AiSaleLineDraft("perfume", 2, 300L))
        val draft = AiIntent.Draft.CreateSaleDraft(
            customerName = "Diego",
            lines = callerLines,
            declaredPaymentCents = 200L,
        )

        callerLines.add(AiSaleLineDraft("crema", 1, 150L))

        assertEquals(listOf(AiSaleLineDraft("perfume", 2, 300L)), draft.lines)
    }

    @Test
    fun `copy of a sale draft also isolates its lines from caller mutation`() {
        val original = AiIntent.Draft.CreateSaleDraft(
            customerName = "Diego",
            lines = listOf(AiSaleLineDraft("perfume", 2, 300L)),
            declaredPaymentCents = 200L,
        )
        val callerLines = mutableListOf(AiSaleLineDraft("crema", 1, 150L))
        val changed = original.copy(lines = callerLines)

        callerLines.add(AiSaleLineDraft("gel", 3, 90L))

        assertEquals(listOf(AiSaleLineDraft("crema", 1, 150L)), changed.lines)
        assertEquals(listOf(AiSaleLineDraft("perfume", 2, 300L)), original.lines)
    }

    @Test
    fun `sale draft lines reject mutation through a MutableList cast`() {
        val draft = AiIntent.Draft.CreateSaleDraft(
            customerName = "Diego",
            lines = listOf(
                AiSaleLineDraft("perfume", 2, 300L),
                AiSaleLineDraft("crema", 1, 150L),
            ),
            declaredPaymentCents = 200L,
        )

        assertThrows(UnsupportedOperationException::class.java) {
            (draft.lines as MutableList<AiSaleLineDraft>).add(AiSaleLineDraft("gel", 3, 90L))
        }

        assertEquals(
            listOf(AiSaleLineDraft("perfume", 2, 300L), AiSaleLineDraft("crema", 1, 150L)),
            draft.lines,
        )
    }
}
