package com.cuentivo.app.ai

const val AI_AVAILABILITY_CALLABLE_NAME = "getAiAvailability"

/**
 * Parses the raw callable response. Returns true if available, false if disabled,
 * or null if data is null/malformed.
 */
fun parseAvailabilityResponse(data: Any?): Boolean? {
    val map = data as? Map<*, *> ?: return null
    return map["available"] as? Boolean
}
