package com.cuentivo.app.ai

interface AiAvailabilityGateway {
    /**
     * Checks whether CuentivoAI is remotely enabled.
     * Returns true if enabled, false if disabled by remote switch,
     * or null if the check failed due to network/error.
     */
    suspend fun checkAvailability(): Boolean?
}
