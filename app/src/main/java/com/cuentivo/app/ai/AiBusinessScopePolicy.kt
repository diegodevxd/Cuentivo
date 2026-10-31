package com.cuentivo.app.ai

import java.util.Locale

/** Outcome of the conservative business-scope policy. */
sealed interface AiBusinessScopeDecision {
    data object ContinueToInterpretation : AiBusinessScopeDecision
    data object OutOfScope : AiBusinessScopeDecision
}

/**
 * Deterministic, business-only conversation policy. Only a tiny allow-list of clearly personal or
 * social whole-input prompts is classified as [AiBusinessScopeDecision.OutOfScope]; everything
 * else — including any ambiguity or business language — continues to typed interpretation.
 *
 * Normalization is exactly `trim().lowercase(Locale.ROOT)`: no accent stripping, no fuzzy
 * matching, no LLM, no network, no I/O. The typed server `OUT_OF_SCOPE` remains the future
 * authority; this policy never calculates balances, inspects customers or creates operations.
 */
object AiBusinessScopePolicy {

    const val OUT_OF_SCOPE_REPLY =
        "Soy CuentivoAI, un asistente para tu negocio. No puedo conversar sobre temas personales, " +
            "pero sí ayudarte con ventas, abonos, clientes, productos, deudas y cobros."

    private val OUT_OF_SCOPE_EXACT = setOf(
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
    )

    fun decide(text: String): AiBusinessScopeDecision =
        if (text.trim().lowercase(Locale.ROOT) in OUT_OF_SCOPE_EXACT) {
            AiBusinessScopeDecision.OutOfScope
        } else {
            AiBusinessScopeDecision.ContinueToInterpretation
        }
}