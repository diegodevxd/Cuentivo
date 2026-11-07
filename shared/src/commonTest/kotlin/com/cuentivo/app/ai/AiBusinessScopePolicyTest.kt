package com.cuentivo.app.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiBusinessScopePolicyTest {

    private fun decide(text: String): AiBusinessScopeDecision = AiBusinessScopePolicy.decide(text)

    @Test
    fun `accented social greeting continues because there is no accent normalization`() {
        assertEquals(
            AiBusinessScopeDecision.ContinueToInterpretation,
            decide("¿Cómo estás?"),
        )
    }

    @Test
    fun `plain social greeting is out of scope`() {
        assertEquals(AiBusinessScopeDecision.OutOfScope, decide("como estas?"))
    }

    @Test
    fun `business language continues`() {
        assertEquals(AiBusinessScopeDecision.ContinueToInterpretation, decide("María abonó 150"))
        assertEquals(AiBusinessScopeDecision.ContinueToInterpretation, decide("hola María"))
        assertEquals(AiBusinessScopeDecision.ContinueToInterpretation, decide("vende dos perfumes"))
    }

    @Test
    fun `exact whole input social prompts are out of scope`() {
        for (prompt in listOf(
            "hola",
            "buenos dias",
            "buenas tardes",
            "buenas noches",
            "como estas",
            "como estas?",
            "¿como estas?",
            "cuentame un chiste",
            "dime un chiste",
            "quien eres",
            "que haces",
        )) {
            assertEquals(AiBusinessScopeDecision.OutOfScope, decide(prompt), "expected OutOfScope for: $prompt")
        }
    }

    @Test
    fun `trim and case are normalized before matching`() {
        assertEquals(AiBusinessScopeDecision.OutOfScope, decide("  hola  "))
        assertEquals(AiBusinessScopeDecision.OutOfScope, decide("HOLA"))
        assertEquals(AiBusinessScopeDecision.OutOfScope, decide("BUENOS DIAS"))
    }

    @Test
    fun `accented variant of an out-of-scope prompt continues`() {
        // No accent normalization: "buenos días" (with accent) is not in the exact allow-list.
        assertEquals(AiBusinessScopeDecision.ContinueToInterpretation, decide("Buenos Días".lowercase()))
    }

    @Test
    fun `reply promises neither registration nor balance`() {
        val reply = AiBusinessScopePolicy.OUT_OF_SCOPE_REPLY
        assertTrue(reply.isNotBlank())
        assertFalse(reply.contains("registré"), "must not promise registration")
        assertFalse(reply.contains("saldo"), "must not promise balance")
        assertTrue(reply.contains("ventas"), "must mention business help")
    }
}
