package com.cuentivo.app.ai

interface AiAvailabilityGateway {
    suspend fun checkAvailability(): Boolean
}
