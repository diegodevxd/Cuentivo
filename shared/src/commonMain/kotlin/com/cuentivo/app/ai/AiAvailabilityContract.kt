package com.cuentivo.app.ai

/**
 * El nombre del callable que dice si CuentivoAI está encendido.
 *
 * Vive en común porque las dos plataformas lo llaman igual; una segunda copia sería una forma de
 * pedir una función que ya no existe sin que ninguna prueba lo note.
 */
const val AI_AVAILABILITY_CALLABLE_NAME = "getAiAvailability"

/**
 * Parses the raw callable response. Returns true if available, false if disabled,
 * or null if data is null/malformed.
 */
fun parseAvailabilityResponse(data: Any?): Boolean? {
    val map = data as? Map<*, *> ?: return null
    return map["available"] as? Boolean
}
