package com.cuentivo.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseCallableAiGatewayTest {

    private val requestId = "123e4567-e89b-12d3-a456-426614174000"

    private fun parse(data: Any?): AiProviderResult = parseCallableData(data, requestId)

    @Test
    fun `valid contract v1 map is accepted and collapses to unavailable`() {
        val result = parse(
            mapOf(
                "contractVersion" to 1,
                "requestId" to requestId,
                "status" to "AI_NOT_CONFIGURED",
            ),
        )
        assertEquals(AiProviderResult.Unavailable("AI_NOT_CONFIGURED"), result)
    }

    @Test
    fun `firebase json numeric types are accepted`() {
        val result = parse(
            mapOf(
                "contractVersion" to 1.0,
                "requestId" to requestId,
                "status" to "AI_NOT_CONFIGURED",
            ),
        )
        assertEquals(AiProviderResult.Unavailable("AI_NOT_CONFIGURED"), result)
    }

    @Test
    fun `wrong contract version is unavailable`() {
        val result = parse(
            mapOf(
                "contractVersion" to 2,
                "requestId" to requestId,
                "status" to "AI_NOT_CONFIGURED",
            ),
        )
        assertEquals(AiProviderResult.Unavailable("AI_NOT_CONFIGURED"), result)
    }

    @Test
    fun `different requestId is unavailable`() {
        val result = parse(
            mapOf(
                "contractVersion" to 1,
                "requestId" to "9a3f2b1c-4d5e-4f6a-8b7c-9d0e1f2a3b4c",
                "status" to "AI_NOT_CONFIGURED",
            ),
        )
        assertEquals(AiProviderResult.Unavailable("AI_NOT_CONFIGURED"), result)
    }

    @Test
    fun `different status is unavailable`() {
        val result = parse(
            mapOf(
                "contractVersion" to 1,
                "requestId" to requestId,
                "status" to "OK",
            ),
        )
        assertEquals(AiProviderResult.Unavailable("AI_NOT_CONFIGURED"), result)
    }

    @Test
    fun `extra field is unavailable`() {
        val result = parse(
            mapOf(
                "contractVersion" to 1,
                "requestId" to requestId,
                "status" to "AI_NOT_CONFIGURED",
                "text" to "vende dos perfumes",
            ),
        )
        assertEquals(AiProviderResult.Unavailable("AI_NOT_CONFIGURED"), result)
    }

    @Test
    fun `non map payloads are unavailable`() {
        for (payload in listOf(null, "AI_NOT_CONFIGURED", 42L, listOf("a", "b"))) {
            assertEquals(
                AiProviderResult.Unavailable("AI_NOT_CONFIGURED"),
                parse(payload),
            )
        }
    }

    @Test
    fun `transport never produces interpreted intents`() {
        val shapes = listOf(
            mapOf("contractVersion" to 1, "requestId" to requestId, "status" to "AI_NOT_CONFIGURED"),
            mapOf("contractVersion" to 1, "requestId" to requestId, "status" to "AI_NOT_CONFIGURED", "intent" to mapOf("type" to "sale")),
            mapOf("contractVersion" to 3, "requestId" to requestId, "status" to "AI_NOT_CONFIGURED"),
            null,
            "raw string",
        )
        for (shape in shapes) {
            val result = parse(shape)
            assertTrue(
                "expected Unavailable, got $result",
                result is AiProviderResult.Unavailable,
            )
        }
    }
}
