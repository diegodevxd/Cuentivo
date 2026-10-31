package com.cuentivo.app.ai

/**
 * Local, off-by-default gate for CuentivoAI.
 *
 * [isEnabled] is false unless an explicit local override is supplied, so no existing
 * Gratis/Pro flow can touch a provider by accident. This object is a pure local seam:
 * it reads no state, opens no network and holds no secrets. Real gating (server flags,
 * entitlement) belongs to later phases and never replaces the default-off posture here.
 */
object AiExperiment {

    /** False by default; true only for an explicit [localOverride]. */
    fun isEnabled(localOverride: Boolean = false): Boolean = localOverride
}
