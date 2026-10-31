package com.cuentivo.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeAiProviderTest {

    private val provider = FakeAiProvider()

    @Test
    fun `recognizes the fixed payment fixture`() = runBlocking {
        val result = provider.interpret(AiInterpretRequest(text = "Diego me pagó 500", requestId = "r-pay"))

        assertEquals(
            AiProviderResult.Interpreted(
                AiIntent.Draft.RecordPaymentDraft(customerName = "Diego", amountCents = 500L),
            ),
            result,
        )
    }

    @Test
    fun `recognizes the fixed sale fixture`() = runBlocking {
        val result = provider.interpret(
            AiInterpretRequest(text = "Diego se llevó dos perfumes de 300 y pagó 200", requestId = "r-sale"),
        )

        assertEquals(
            AiProviderResult.Interpreted(
                AiIntent.Draft.CreateSaleDraft(
                    customerName = "Diego",
                    lines = listOf(AiSaleLineDraft(productName = "perfume", quantity = 2, unitPriceCents = 300L)),
                    declaredPaymentCents = 200L,
                ),
            ),
            result,
        )
    }

    @Test
    fun `any other text yields clarification`() = runBlocking {
        val unsupported = listOf(
            "¿Cuánto me debe Diego?",
            "Diego me pagó 499",
            "diego me pagó 500",
            "Diego se llevó un perfume de 300",
            "",
            "   Diego me pagó 500   ",
        )

        unsupported.forEachIndexed { index, text ->
            assertEquals(
                AiProviderResult.Interpreted(AiIntent.NeedClarification),
                provider.interpret(AiInterpretRequest(text = text, requestId = "r-$index")),
            )
        }
    }

    @Test
    fun `is deterministic for repeated identical requests`() = runBlocking {
        val request = AiInterpretRequest(text = "Diego me pagó 500", requestId = "r-repeat")

        val first = provider.interpret(request)
        val second = provider.interpret(request)

        assertEquals(first, second)
    }
}
