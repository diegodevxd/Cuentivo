package com.cuentivo.app.ai

/**
 * Deterministic in-memory provider for tests and demos.
 *
 * Recognizes exactly the two fixed fixture inputs by exact text match (no normalization);
 * every other text yields [AiIntent.NeedClarification]. It computes no totals, reads no
 * entities and persists nothing — the fixture map is the single source of its behavior.
 */
class FakeAiProvider : AiProvider {

    override suspend fun interpret(request: AiInterpretRequest): AiProviderResult =
        AiProviderResult.Interpreted(fixtures[request.text] ?: AiIntent.NeedClarification)

    private companion object {
        val fixtures: Map<String, AiIntent> = mapOf(
            "Diego me pagó 500" to AiIntent.Draft.RecordPaymentDraft(
                customerName = "Diego",
                amountCents = 500L,
            ),
            "Diego se llevó dos perfumes de 300 y pagó 200" to AiIntent.Draft.CreateSaleDraft(
                customerName = "Diego",
                lines = listOf(
                    AiSaleLineDraft(
                        productName = "perfume",
                        quantity = 2,
                        unitPriceCents = 300L,
                    ),
                ),
                declaredPaymentCents = 200L,
            ),
        )
    }
}
