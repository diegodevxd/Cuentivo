package com.cuentivo.app.ai

/**
 * Boundary behind which any real inference provider will live.
 *
 * Implementations must be pure interpretation: never write Room, Firestore, money,
 * entitlements or audit; never read entity state; never perform a final financial action.
 * They return immutable [AiProviderResult]s that later phases validate before preview.
 */
interface AiProvider {

    suspend fun interpret(request: AiInterpretRequest): AiProviderResult
}
